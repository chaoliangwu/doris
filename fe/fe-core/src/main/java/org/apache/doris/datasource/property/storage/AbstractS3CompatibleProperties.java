// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License.  You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied.  See the License for the
// specific language governing permissions and limitations
// under the License.

package org.apache.doris.datasource.property.storage;

import org.apache.doris.common.UserException;
import org.apache.doris.datasource.property.ConnectorProperty;

import com.google.common.base.Strings;
import lombok.Getter;
import lombok.Setter;
import org.apache.commons.lang3.StringUtils;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.AwsSessionCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Abstract base class for object storage system properties. This class provides common configuration
 * settings for object storage systems and supports conversion of these properties into configuration
 * maps for different protocols, such as AWS S3. All object storage systems should extend this class
 * to inherit the common configuration properties and methods.
 * <p>
 * The properties include connection settings (e.g., timeouts and maximum connections) and a flag to
 * determine if path-style URLs should be used for the storage system.
 */
public abstract class AbstractS3CompatibleProperties extends StorageProperties implements ObjectStorageProperties {

    /**
     * The maximum number of concurrent connections that can be made to the object storage system.
     * This value is optional and can be configured by the user.
     */
    @Getter
    @ConnectorProperty(names = {"connection.maximum"}, required = false, description = "Maximum number of connections.")
    protected String maxConnections = "100";

    /**
     * The timeout (in milliseconds) for requests made to the object storage system.
     * This value is optional and can be configured by the user.
     */
    @Getter
    @ConnectorProperty(names = {"connection.request.timeout"}, required = false,
            description = "Request timeout in seconds.")
    protected String requestTimeoutS = "10000";

    /**
     * The timeout (in milliseconds) for establishing a connection to the object storage system.
     * This value is optional and can be configured by the user.
     */
    @Getter
    @ConnectorProperty(names = {"connection.timeout"}, required = false, description = "Connection timeout in seconds.")
    protected String connectionTimeoutS = "10000";

    /**
     * Flag indicating whether to use path-style URLs for the object storage system.
     * This value is optional and can be configured by the user.
     */
    @Setter
    @Getter
    @ConnectorProperty(names = {"use_path_style", "s3.path-style-access"}, required = false,
            description = "Whether to use path style URL for the storage.")
    protected String usePathStyle = "false";
    @ConnectorProperty(names = {"force_parsing_by_standard_uri"}, required = false,
            description = "Whether to use path style URL for the storage.")
    @Setter
    @Getter
    protected String forceParsingByStandardUrl = "false";

    @Getter
    @ConnectorProperty(names = {"s3.session_token", "session_token"},
            required = false,
            description = "The session token of S3.")
    protected String sessionToken = "";

    /**
     * Constructor to initialize the object storage properties with the provided type and original properties map.
     *
     * @param type      the type of object storage system.
     * @param origProps the original properties map.
     */
    protected AbstractS3CompatibleProperties(Type type, Map<String, String> origProps) {
        super(type, origProps);
    }

    /**
     * Generates a map of AWS S3 configuration properties specifically for Backend (BE) service usage.
     * This configuration includes endpoint, region, access credentials, timeouts, and connection settings.
     * The map is typically used to initialize S3-compatible storage access for the backend.
     *
     * @param maxConnections      the maximum number of allowed S3 connections.
     * @param requestTimeoutMs    request timeout in milliseconds.
     * @param connectionTimeoutMs connection timeout in milliseconds.
     * @param usePathStyle        whether to use path-style access (true/false).
     * @return a map containing AWS S3 configuration properties.
     */
    protected Map<String, String> generateBackendS3Configuration(String maxConnections,
                                                                 String requestTimeoutMs,
                                                                 String connectionTimeoutMs,
                                                                 String usePathStyle) {
        return doBuildS3Configuration(maxConnections, requestTimeoutMs, connectionTimeoutMs, usePathStyle);
    }

    /**
     * Overloaded version of {@link #generateBackendS3Configuration(String, String, String, String)}
     * that uses default values
     * from the current object context for connection settings.
     *
     * @return a map containing AWS S3 configuration properties.
     */
    protected Map<String, String> generateBackendS3Configuration() {
        return doBuildS3Configuration(maxConnections, requestTimeoutS, connectionTimeoutS, usePathStyle);
    }

    /**
     * Internal method to centralize S3 configuration property assembly.
     */
    private Map<String, String> doBuildS3Configuration(String maxConnections,
                                                       String requestTimeoutMs,
                                                       String connectionTimeoutMs,
                                                       String usePathStyle) {
        Map<String, String> s3Props = new HashMap<>();
        s3Props.put("AWS_ENDPOINT", getEndpoint());
        s3Props.put("AWS_REGION", getRegion());
        s3Props.put("AWS_ACCESS_KEY", getAccessKey());
        s3Props.put("AWS_SECRET_KEY", getSecretKey());
        s3Props.put("AWS_MAX_CONNECTIONS", maxConnections);
        s3Props.put("AWS_REQUEST_TIMEOUT_MS", requestTimeoutMs);
        s3Props.put("AWS_CONNECTION_TIMEOUT_MS", connectionTimeoutMs);
        s3Props.put("use_path_style", usePathStyle);
        if (StringUtils.isNotBlank(getSessionToken())) {
            s3Props.put("AWS_TOKEN", getSessionToken());
        }
        return s3Props;
    }

    @Override
    public Map<String, String> getBackendConfigProperties() {
        return generateBackendS3Configuration();
    }

    public AwsCredentialsProvider getAwsCredentialsProvider() {
        if (StringUtils.isNotBlank(getAccessKey()) && StringUtils.isNotBlank(getSecretKey())) {
            if (Strings.isNullOrEmpty(sessionToken)) {
                return StaticCredentialsProvider.create(AwsBasicCredentials.create(getAccessKey(), getSecretKey()));
            } else {
                return StaticCredentialsProvider.create(AwsSessionCredentials.create(getAccessKey(), getSecretKey(),
                        sessionToken));
            }
        }
        return null;
    }


    @Override
    public void initNormalizeAndCheckProps() {
        super.initNormalizeAndCheckProps();
        setEndpointIfNotSet();
        if (!isValidEndpoint(getEndpoint())) {
            throw new IllegalArgumentException("Invalid endpoint format: " + getEndpoint());
        }
        checkRequiredProperties();
        initRegionIfNecessary();
        if (StringUtils.isBlank(getRegion())) {
            throw new IllegalArgumentException("region is required");
        }
    }

    private void initRegionIfNecessary() {
        if (StringUtils.isNotBlank(getRegion())) {
            return;
        }
        String endpoint = getEndpoint();
        if (endpoint == null || endpoint.isEmpty()) {
            throw new IllegalArgumentException("endpoint is required");
        }
        Optional<String> regionOptional = extractRegion(endpoint);
        if (regionOptional.isPresent()) {
            setRegion(regionOptional.get());
            return;
        }
        throw new IllegalArgumentException("Not a valid region, and cannot be parsed from endpoint: " + endpoint);
    }

    public Optional<String> extractRegion(String endpoint) {
        for (Pattern pattern : endpointPatterns()) {
            Matcher matcher = pattern.matcher(endpoint.toLowerCase());
            if (matcher.matches()) {
                // Check all possible groups for region (group 1, 2, or 3)
                for (int i = 1; i <= matcher.groupCount(); i++) {
                    String group = matcher.group(i);
                    if (StringUtils.isNotBlank(group)) {
                        return Optional.of(group);
                    }
                }
            }
        }
        return Optional.empty();
    }

    protected abstract Set<Pattern> endpointPatterns();

    private boolean isValidEndpoint(String endpoint) {
        if (StringUtils.isBlank(endpoint)) {
            return false;
        }
        for (Pattern pattern : endpointPatterns()) {
            Matcher matcher = pattern.matcher(endpoint.toLowerCase());
            if (matcher.matches()) {
                return true;
            }
        }
        return false;
    }

    private void setEndpointIfNotSet() {
        if (StringUtils.isNotBlank(getEndpoint())) {
            return;
        }
        String endpoint = S3PropertyUtils.constructEndpointFromUrl(origProps, usePathStyle, forceParsingByStandardUrl);
        if (StringUtils.isBlank(endpoint)) {
            throw new IllegalArgumentException("endpoint is required");
        }
        setEndpoint(endpoint);
    }

    @Override
    public String validateAndNormalizeUri(String uri) throws UserException {
        return S3PropertyUtils.validateAndNormalizeUri(uri, getUsePathStyle(), getForceParsingByStandardUrl());

    }

    @Override
    public String validateAndGetUri(Map<String, String> loadProps) throws UserException {
        return S3PropertyUtils.validateAndGetUri(loadProps);
    }

    @Override
    public String getStorageName() {
        return "S3";
    }
}
