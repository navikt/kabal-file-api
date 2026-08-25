package no.nav.klage.service

import com.google.auth.oauth2.ServiceAccountCredentials
import com.google.cloud.storage.BlobId
import com.google.cloud.storage.BlobInfo
import com.google.cloud.storage.PostPolicyV4
import com.google.cloud.storage.StorageOptions
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.security.KeyPairGenerator
import java.security.interfaces.RSAPrivateKey
import java.util.Base64
import java.util.concurrent.TimeUnit

class UploadPostPolicyTest {

    private fun storage() = StorageOptions.newBuilder()
        .setProjectId("test-project")
        .setCredentials(
            ServiceAccountCredentials.newBuilder()
                .setClientEmail("test@test-project.iam.gserviceaccount.com")
                .setPrivateKeyId("key-id")
                .setPrivateKey(
                    KeyPairGenerator.getInstance("RSA")
                        .apply { initialize(2048) }
                        .generateKeyPair().private as RSAPrivateKey
                )
                .setProjectId("test-project")
                .build()
        )
        .build()
        .service

    @Test
    fun `upload policy enforces a max size and content type`() {
        val maxSize = 536870912

        val policy = storage().generateSignedPostPolicyV4(
            BlobInfo.newBuilder(BlobId.of("test-bucket", "document/abc")).build(),
            30, TimeUnit.MINUTES,
            PostPolicyV4.PostFieldsV4.newBuilder()
                .setContentType("application/pdf")
                .build(),
            PostPolicyV4.PostConditionsV4.newBuilder()
                .addContentLengthRangeCondition(1, maxSize)
                .build(),
        )

        val decodedPolicy = String(Base64.getDecoder().decode(policy.fields["policy"]))

        assertThat(decodedPolicy).contains("""["content-length-range",1,$maxSize]""")
        assertThat(decodedPolicy).contains("""{"content-type":"application/pdf"}""")
        assertThat(decodedPolicy).contains("""{"key":"document/abc"}""")

        assertThat(policy.fields).containsKeys("policy", "x-goog-signature", "key", "content-type")
        assertThat(policy.fields).doesNotContainKey("content-length-range")
    }
}
