package com.thewinterframework.configurate.config;

import org.junit.jupiter.api.Test;

import javax.lang.model.SourceVersion;

import static org.junit.jupiter.api.Assertions.*;

class ConfigurateIdentifiersTest {

    @Test
    void keepsLettersDigitsAndUnderscores() {
        assertEquals("lang", ConfigurateIdentifiers.toIdentifier("lang"));
        assertEquals("my_config2", ConfigurateIdentifiers.toIdentifier("my_config2"));
    }

    @Test
    void replacesDashesDotsAndSlashes() {
        assertEquals("my_config_v2", ConfigurateIdentifiers.toIdentifier("my-config.v2"));
        assertEquals("a_b", ConfigurateIdentifiers.toIdentifier("a/b"));
    }

    @Test
    void prefixesAGeneratedNameThatStartsWithADigit() {
        assertEquals("_2fa", ConfigurateIdentifiers.toIdentifier("2fa"));
    }

    @Test
    void emptyNameGetsAPrefix() {
        assertEquals("_", ConfigurateIdentifiers.toIdentifier(""));
    }

    @Test
    void nonAsciiCharactersBecomeUnderscores() {
        assertEquals("_bung", ConfigurateIdentifiers.toIdentifier("übung"));
    }

    @Test
    void resultIsAJavaIdentifier() {
        for (final var fileName : new String[]{"config", "my-config.v2", "2fa", "a b", "ünï", "../x", "class"}) {
            final var identifier = ConfigurateIdentifiers.toIdentifier(fileName);
            assertTrue(SourceVersion.isIdentifier(identifier + "ContainerProvider"), identifier);
        }
    }
}
