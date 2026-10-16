package main

import (
	"encoding/json"
	"strings"
	"testing"

	"github.com/minio/madmin-go/v3"
)

func TestRuntimePolicyOnlyGrantsObjectActionsInsideAssignedPrefix(t *testing.T) {
	policy := runtimePolicy()
	statements, ok := policy["Statement"].([]map[string]any)
	if !ok || len(statements) != 2 {
		t.Fatalf("expected object and constrained listing statements, got %#v", policy["Statement"])
	}
	encoded, err := json.Marshal(policy)
	if err != nil {
		t.Fatal(err)
	}
	if got := string(encoded); got == "" ||
		!containsAll(got, "s3:GetObject", "s3:PutObject", "s3:DeleteObject", "s3:DeleteObjectVersion", "artio-objects/objects/*") {
		t.Fatalf("runtime policy is missing required scoped operations: %s", got)
	}
	for _, forbidden := range []string{"s3:*", "admin:", "iam:", "policy:", "artio-objects/*"} {
		if strings.Contains(string(encoded), forbidden) {
			t.Fatalf("runtime policy unexpectedly contains %q: %s", forbidden, encoded)
		}
	}
	condition, ok := statements[1]["Condition"].(map[string]any)
	if !ok || condition["StringLike"] == nil {
		t.Fatalf("bucket listing is not constrained to the assigned prefix: %#v", statements[1])
	}
}

func TestSafeAdminErrorCodeDoesNotReturnProviderMessage(t *testing.T) {
	provider := madmin.ErrorResponse{Code: "AccessDenied", Message: "private service account key was rejected"}
	if got := safeAdminErrorCode(provider); got != "AccessDenied" {
		t.Fatalf("expected sanitized provider code, got %q", got)
	}
	if got := safeAdminErrorCode(assertError("provider returned secret material")); got != "request-failed" {
		t.Fatalf("unexpected fallback error code %q", got)
	}
}

type testError string

func (e testError) Error() string { return string(e) }

func assertError(message string) error { return testError(message) }

func containsAll(value string, parts ...string) bool {
	for _, part := range parts {
		if !strings.Contains(value, part) {
			return false
		}
	}
	return true
}
