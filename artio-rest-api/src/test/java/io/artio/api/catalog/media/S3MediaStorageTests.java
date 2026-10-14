package io.artio.api.catalog.media;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;

class S3MediaStorageTests {
    @Test void storesReadsAndDeletesPrivateObjectsUnderConfiguredPrefix() throws Exception {
        S3Client client = mock(S3Client.class);
        var storage = new S3MediaStorage(client, "artio-media", "/catalog/products/");
        String key = "12345678-1234-1234-1234-123456789abc.png";
        byte[] image = { 1, 2, 3 };
        storage.store(key, image);
        var put = ArgumentCaptor.forClass(PutObjectRequest.class);
        var body = ArgumentCaptor.forClass(RequestBody.class);
        verify(client).putObject(put.capture(), body.capture());
        assertThat(put.getValue().bucket()).isEqualTo("artio-media");
        assertThat(put.getValue().key()).isEqualTo("catalog/products/" + key);
        assertThat(put.getValue().contentType()).isEqualTo("image/png");
        assertThat(put.getValue().acl()).isNull();
        assertThat(body.getValue().contentStreamProvider().newStream().readAllBytes()).containsExactly(image);
        when(client.getObjectAsBytes(any(GetObjectRequest.class))).thenReturn(ResponseBytes.fromByteArray(GetObjectResponse.builder().build(), image));
        assertThat(storage.read(key)).containsExactly(image);
        storage.delete(key);
        verify(client).deleteObject(DeleteObjectRequest.builder().bucket("artio-media").key("catalog/products/"+key).build());
        storage.close();
        verify(client).close();
    }
    @Test void rejectsTraversalBeforeContactingStorage() {
        S3Client client = mock(S3Client.class);
        var storage = new S3MediaStorage(client, "artio-media", "products");
        assertThatThrownBy(() -> storage.read("../private.png")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> storage.delete(null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new S3MediaStorage(client,"artio-media","../private")).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(client);
    }
}
