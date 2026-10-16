package main

import (
	"bytes"
	"context"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"log"
	"os"
	"path/filepath"
	"strings"
	"time"

	"github.com/minio/madmin-go/v3"
	"github.com/minio/minio-go/v7"
	"github.com/minio/minio-go/v7/pkg/credentials"
)

const (
	bucketName           = "artio-objects"
	objectPrefix         = "objects/"
	rootUserFile         = "/run/secrets/minio-root-user"
	rootPasswordFile     = "/run/secrets/minio-root-password"
	runtimeAccessKeyFile = "/run/secrets/runtime-access-key"
	runtimeSecretKeyFile = "/run/secrets/runtime-secret-key"
)

// Separate locked images use these stages so the old identity is removed only
// after Core has started and health-checked the replacement adapter.
var lifecycleStage = "provision"

func main() {
	if err := provision(); err != nil {
		// All returned errors are locally defined safe stage messages; SDK/server details stay hidden.
		log.Printf("MinIO provisioning failed: %v", err)
		os.Exit(1)
	}
}

func provision() error {
	rootUser, err := secret(rootUserFile)
	if err != nil {
		return err
	}
	rootPassword, err := secret(rootPasswordFile)
	if err != nil {
		return err
	}
	runtimeAccessKey, err := secret(runtimeAccessKeyFile)
	if err != nil {
		return err
	}
	runtimeSecretKey, err := secret(runtimeSecretKeyFile)
	if err != nil {
		return err
	}
	if rootUser == "minioadmin" || rootPassword == "minioadmin" ||
		len(runtimeAccessKey) < 16 || len(runtimeSecretKey) < 16 {
		return errors.New("credential policy validation failed")
	}

	host := os.Getenv("ARTIO_EXTENSION_SERVICE_MINIO_HOST")
	port := os.Getenv("ARTIO_EXTENSION_SERVICE_MINIO_PORT")
	if host == "" || port != "9000" || strings.ContainsAny(host, "/:@") {
		return errors.New("managed MinIO service address is invalid")
	}
	endpoint := host + ":" + port

	ctx, cancel := context.WithTimeout(context.Background(), 90*time.Second)
	defer cancel()
	rootClient, err := minio.New(endpoint, &minio.Options{
		Creds: credentials.NewStaticV4(rootUser, rootPassword, ""), Secure: false,
	})
	if err != nil {
		return errors.New("could not initialize managed MinIO client")
	}
	adminClient, err := madmin.New(endpoint, rootUser, rootPassword, false)
	if err != nil {
		return errors.New("could not initialize managed MinIO administrator client")
	}
	if err := waitForMinIO(ctx, rootClient); err != nil {
		return err
	}
	if lifecycleStage == "cleanup" {
		if err := verifyScopedIdentity(ctx, endpoint, runtimeAccessKey, runtimeSecretKey); err != nil {
			return err
		}
		return removeSupersededRuntimeIdentities(ctx, adminClient, rootUser, runtimeAccessKey)
	}
	if lifecycleStage != "provision" {
		return errors.New("provisioning lifecycle stage is invalid")
	}

	exists, err := rootClient.BucketExists(ctx, bucketName)
	if err != nil {
		return errors.New("could not inspect the dedicated bucket")
	}
	if !exists {
		if err := rootClient.MakeBucket(ctx, bucketName, minio.MakeBucketOptions{Region: "us-east-1"}); err != nil {
			return errors.New("could not create the dedicated bucket")
		}
	}
	// Managed mode owns this isolated bucket. Removing a prior bucket policy ensures that
	// anonymous access remains disabled; the scoped service account policy grants runtime access.
	if err := rootClient.SetBucketPolicy(ctx, bucketName, ""); err != nil {
		return errors.New("could not enforce a private bucket policy")
	}
	if err := rootClient.SetBucketVersioning(ctx, bucketName, minio.BucketVersioningConfiguration{Status: "Enabled"}); err != nil {
		return errors.New("could not enable object versioning")
	}

	policy, err := json.Marshal(runtimePolicy())
	if err != nil {
		return errors.New("could not build scoped object policy")
	}
	accounts, err := adminClient.ListServiceAccounts(ctx, rootUser)
	if err != nil {
		return errors.New("could not reconcile runtime identity")
	}
	found := false
	for _, account := range accounts.Accounts {
		if account.AccessKey == runtimeAccessKey {
			found = true
			break
		}
	}
	if !found {
		_, err = adminClient.AddServiceAccount(ctx, madmin.AddServiceAccountReq{
			TargetUser:  rootUser,
			AccessKey:   runtimeAccessKey,
			SecretKey:   runtimeSecretKey,
			Name:        "artio-storage",
			Description: "Artio object storage adapter identity",
			Policy:      policy,
		})
		if err != nil {
			return fmt.Errorf("could not create the scoped runtime identity: %s", safeAdminErrorCode(err))
		}
	}
	return verifyScopedIdentity(ctx, endpoint, runtimeAccessKey, runtimeSecretKey)
}

func removeSupersededRuntimeIdentities(ctx context.Context, adminClient *madmin.AdminClient, rootUser, currentAccessKey string) error {
	accounts, err := adminClient.ListServiceAccounts(ctx, rootUser)
	if err != nil {
		return errors.New("could not inspect runtime identities for rotation cleanup")
	}
	for _, account := range accounts.Accounts {
		if account.Name != "artio-storage" || account.AccessKey == currentAccessKey {
			continue
		}
		if err := adminClient.DeleteServiceAccount(ctx, account.AccessKey); err != nil {
			return errors.New("could not revoke a superseded runtime identity")
		}
	}
	return nil
}

func safeAdminErrorCode(err error) string {
	var response madmin.ErrorResponse
	if errors.As(err, &response) && response.Code != "" {
		return response.Code
	}
	return "request-failed"
}

func secret(path string) (string, error) {
	contents, err := os.ReadFile(path)
	if err != nil {
		return "", fmt.Errorf("required protected credential file is unavailable: %s", filepath.Base(path))
	}
	value := strings.TrimSpace(string(contents))
	clear(contents)
	if value == "" || strings.ContainsAny(value, "\r\n\x00") {
		return "", errors.New("a required protected credential file is invalid")
	}
	return value, nil
}

func waitForMinIO(ctx context.Context, client *minio.Client) error {
	ticker := time.NewTicker(500 * time.Millisecond)
	defer ticker.Stop()
	for {
		probeCtx, cancel := context.WithTimeout(ctx, 3*time.Second)
		_, err := client.BucketExists(probeCtx, bucketName)
		cancel()
		if err == nil {
			return nil
		}
		select {
		case <-ctx.Done():
			return errors.New("managed MinIO did not become ready before the provisioning deadline")
		case <-ticker.C:
		}
	}
}

func runtimePolicy() map[string]any {
	return map[string]any{
		"Version": "2012-10-17",
		"Statement": []map[string]any{
			{
				"Effect":   "Allow",
				"Action":   []string{"s3:GetObject", "s3:PutObject", "s3:DeleteObject", "s3:DeleteObjectVersion"},
				"Resource": []string{"arn:aws:s3:::" + bucketName + "/" + objectPrefix + "*"},
			},
			{
				"Effect":    "Allow",
				"Action":    []string{"s3:ListBucket"},
				"Resource":  []string{"arn:aws:s3:::" + bucketName},
				"Condition": map[string]any{"StringLike": map[string]any{"s3:prefix": []string{strings.TrimSuffix(objectPrefix, "/"), objectPrefix + "*"}}},
			},
		},
	}
}

func verifyScopedIdentity(ctx context.Context, endpoint, accessKey, secretKey string) error {
	scoped, err := minio.New(endpoint, &minio.Options{
		Creds: credentials.NewStaticV4(accessKey, secretKey, ""), Secure: false,
	})
	if err != nil {
		return errors.New("could not initialize scoped identity check")
	}
	key := objectPrefix + ".artio-diagnostics/provisioning-check"
	body := []byte("artio-minio-provisioning-check-v1")
	digest := sha256.Sum256(body)
	result, err := scoped.PutObject(ctx, bucketName, key, bytes.NewReader(body), int64(len(body)), minio.PutObjectOptions{
		ContentType: "application/octet-stream", UserMetadata: map[string]string{"artio-sha256": hex.EncodeToString(digest[:])},
	})
	if err != nil {
		return errors.New("scoped identity could not write within its assigned prefix")
	}
	removed := false
	defer func() {
		if !removed {
			_ = scoped.RemoveObject(context.Background(), bucketName, key,
				minio.RemoveObjectOptions{VersionID: result.VersionID})
		}
	}()
	object, err := scoped.GetObject(ctx, bucketName, key, minio.GetObjectOptions{VersionID: result.VersionID})
	if err != nil {
		return errors.New("scoped identity could not read its diagnostic object")
	}
	readback, readErr := io.ReadAll(io.LimitReader(object, int64(len(body)+1)))
	closeErr := object.Close()
	if readErr != nil || closeErr != nil || !bytes.Equal(readback, body) {
		return errors.New("scoped identity readback failed integrity verification")
	}
	if _, err = scoped.StatObject(ctx, bucketName, key, minio.StatObjectOptions{VersionID: result.VersionID}); err != nil {
		return errors.New("scoped identity could not inspect its diagnostic object")
	}
	deniedKey := "outside-prefix/should-be-denied"
	_, err = scoped.PutObject(ctx, bucketName, deniedKey, bytes.NewReader(body), int64(len(body)), minio.PutObjectOptions{})
	if err == nil {
		_ = scoped.RemoveObject(ctx, bucketName, deniedKey, minio.RemoveObjectOptions{})
		return errors.New("runtime identity can write outside its assigned prefix")
	}
	if err = scoped.RemoveObject(ctx, bucketName, key, minio.RemoveObjectOptions{VersionID: result.VersionID}); err != nil {
		return errors.New("scoped identity could not delete its diagnostic object")
	}
	removed = true
	return nil
}
