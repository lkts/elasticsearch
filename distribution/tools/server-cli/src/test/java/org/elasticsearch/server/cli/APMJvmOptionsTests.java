/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the "Elastic License
 * 2.0", the "GNU Affero General Public License v3.0 only", and the "Server Side
 * Public License v 1"; you may not use this file except in compliance with, at
 * your election, the "Elastic License 2.0", the "GNU Affero General Public
 * License v3.0 only", or the "Server Side Public License, v 1".
 */

package org.elasticsearch.server.cli;

import org.elasticsearch.cli.UserException;
import org.elasticsearch.common.settings.MockSecureSettings;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.monitor.jvm.JvmInfo;
import org.elasticsearch.node.Node;
import org.elasticsearch.test.ESTestCase;
import org.junit.After;
import org.junit.Before;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.elasticsearch.test.MapMatcher.matchesMap;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.endsWith;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasEntry;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;

public class APMJvmOptionsTests extends ESTestCase {

    private Path installDir;
    private Path agentPath;

    @Before
    public void setup() throws IOException, UserException {
        installDir = makeFakeAgentJar();
        agentPath = APMJvmOptions.findAgentJar(installDir.toAbsolutePath().toString());
    }

    @After
    public void cleanup() throws IOException {
        Files.delete(agentPath);
    }

    public void testFindJar() throws IOException {
        assertNotNull(agentPath);

        Path anotherPath = Files.createDirectories(installDir.resolve("another"));
        Path apmPathDir = anotherPath.resolve("modules").resolve("apm");
        Files.createDirectories(apmPathDir);

        assertTrue(
            expectThrows(UserException.class, () -> APMJvmOptions.findAgentJar(anotherPath.toAbsolutePath().toString())).getMessage()
                .contains("Installation is corrupt")
        );
    }

    public void testFileDeleteWorks() throws IOException {
        var tempFile = APMJvmOptions.createTemporaryPropertiesFile(agentPath.getParent());
        var commandLineOption = APMJvmOptions.agentCommandLineOption(agentPath, tempFile);
        var jvmInfo = mock(JvmInfo.class);
        doReturn(new String[] { commandLineOption }).when(jvmInfo).getInputArguments();
        assertTrue(Files.exists(tempFile));
        Node.deleteTemporaryApmConfig(jvmInfo, (e, p) -> fail("Shouldn't hit an exception"));
        assertFalse(Files.exists(tempFile));
    }

    public void testExtractSecureSettings() {
        MockSecureSettings secureSettings = new MockSecureSettings();
        secureSettings.setString("telemetry.secret_token", "token");
        secureSettings.setString("telemetry.api_key", "key");

        Map<String, String> propertiesMap = new HashMap<>();
        APMJvmOptions.extractSecureSettings(secureSettings, propertiesMap);

        assertThat(propertiesMap, matchesMap(Map.of("secret_token", "token", "api_key", "key")));
    }

    public void testExtractSettings() throws UserException {
        Settings defaults = Settings.builder()
            .put("telemetry.agent.server_url", "https://myurl:443")
            .put("telemetry.agent.service_node_name", "instance-0000000001")
            .build();

        var name = "APM Tracing";
        var deploy = "123";
        var org = "456";
        var extracted = APMJvmOptions.extractApmSettings(
            Settings.builder()
                .put(defaults)
                .put("telemetry.agent.global_labels.deployment_name", name)
                .put("telemetry.agent.global_labels.deployment_id", deploy)
                .put("telemetry.agent.global_labels.organization_id", org)
                .build()
        );

        assertThat(
            extracted,
            allOf(
                hasEntry("server_url", "https://myurl:443"),
                hasEntry("service_node_name", "instance-0000000001"),
                hasEntry(equalTo("global_labels"), not(endsWith(","))), // test that we have collapsed all global labels into one
                not(hasKey("global_labels.organization_id")) // tests that we strip out the top level label keys
            )
        );

        List<String> labels = Arrays.stream(extracted.get("global_labels").split(",")).toList();
        assertThat(labels, hasSize(3));
        assertThat(labels, containsInAnyOrder("deployment_name=APM Tracing", "organization_id=" + org, "deployment_id=" + deploy));

        // test replacing with underscores and skipping empty
        name = "APM=Tracing";
        deploy = "";
        org = ",456";
        extracted = APMJvmOptions.extractApmSettings(
            Settings.builder()
                .put(defaults)
                .put("telemetry.agent.global_labels.deployment_name", name)
                .put("telemetry.agent.global_labels.deployment_id", deploy)
                .put("telemetry.agent.global_labels.organization_id", org)
                .build()
        );
        labels = Arrays.stream(extracted.get("global_labels").split(",")).toList();
        assertThat(labels, hasSize(2));
        assertThat(labels, containsInAnyOrder("deployment_name=APM_Tracing", "organization_id=_456"));
    }

    private Path makeFakeAgentJar() throws IOException {
        Path tempFile = createTempFile();
        Path apmPathDir = tempFile.getParent().resolve("modules").resolve("apm");
        Files.createDirectories(apmPathDir);
        Path apmAgentFile = apmPathDir.resolve("elastic-apm-agent-0.0.0.jar");
        Files.move(tempFile, apmAgentFile);

        return tempFile.getParent();
    }
}
