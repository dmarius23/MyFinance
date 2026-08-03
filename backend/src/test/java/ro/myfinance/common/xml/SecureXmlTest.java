package ro.myfinance.common.xml;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;

class SecureXmlTest {

    private static Document parse(String xml) throws Exception {
        return SecureXml.hardenedDocumentBuilderFactory().newDocumentBuilder()
                .parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void parsesOrdinaryXml() throws Exception {
        Document d = parse("<root><a>hi</a></root>");
        assertThat(d.getDocumentElement().getNodeName()).isEqualTo("root");
    }

    @Test
    void rejectsAnyDoctypeSoXxeCannotBeDeclared() {
        String withDoctype = "<?xml version=\"1.0\"?><!DOCTYPE root [<!ELEMENT root ANY>]><root/>";
        assertThatThrownBy(() -> parse(withDoctype)).hasMessageContaining("DOCTYPE");
    }

    @Test
    void doesNotLeakLocalFilesViaExternalEntity() throws Exception {
        Path secret = Files.createTempFile("xxe-secret", ".txt");
        Files.writeString(secret, "TOP-SECRET");
        try {
            String payload = "<?xml version=\"1.0\"?>"
                    + "<!DOCTYPE r [<!ENTITY x SYSTEM \"file://" + secret.toAbsolutePath() + "\">]>"
                    + "<r>&x;</r>";
            // The DOCTYPE is refused before any entity is resolved, so the file contents can never surface.
            assertThatThrownBy(() -> parse(payload)).hasMessageContaining("DOCTYPE");
        } finally {
            Files.deleteIfExists(secret);
        }
    }
}
