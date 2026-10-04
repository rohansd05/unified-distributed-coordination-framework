package com.udcf.web;

import org.w3c.dom.Document;
import org.w3c.dom.Node;

import javax.xml.parsers.DocumentBuilderFactory;
import java.nio.file.Files;
import java.nio.file.Path;

/** Test helper: reads the project version straight from backend/pom.xml. */
final class PomVersion {

    private PomVersion() {
    }

    /** The {@code <version>} that is a direct child of {@code <project>}. */
    static String read() {
        Path pom = Path.of("pom.xml");
        if (!Files.exists(pom)) {
            pom = Path.of("backend", "pom.xml");
        }
        try {
            Document document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(pom.toFile());
            for (Node child = document.getDocumentElement().getFirstChild(); child != null;
                 child = child.getNextSibling()) {
                if ("version".equals(child.getNodeName())) {
                    return child.getTextContent().trim();
                }
            }
        } catch (Exception e) {
            throw new IllegalStateException("Cannot read the version from " + pom.toAbsolutePath(), e);
        }
        throw new IllegalStateException("No project version in " + pom.toAbsolutePath());
    }
}
