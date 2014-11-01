/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.jclouds.oauth.v2.functions;

import static com.google.common.base.Preconditions.checkState;
import static org.jclouds.oauth.v2.OAuthConstants.ADDITIONAL_CLAIMS;
import static org.jclouds.oauth.v2.config.OAuthProperties.AUDIENCE;
import static org.jclouds.oauth.v2.config.OAuthProperties.SCOPES;
import static org.jclouds.oauth.v2.config.OAuthProperties.SIGNATURE_OR_MAC_ALGORITHM;
import static org.jclouds.oauth.v2.domain.Claims.EXPIRATION_TIME;
import static org.jclouds.oauth.v2.domain.Claims.ISSUED_AT;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import org.jclouds.Constants;
import org.jclouds.oauth.v2.config.OAuthScopes;
import org.jclouds.oauth.v2.domain.Header;
import org.jclouds.oauth.v2.domain.OAuthCredentials;
import org.jclouds.oauth.v2.domain.TokenRequest;
import org.jclouds.rest.internal.GeneratedHttpRequest;

import com.google.common.base.Function;
import com.google.common.base.Joiner;
import com.google.common.base.Supplier;
import com.google.common.reflect.Invokable;
import com.google.inject.Inject;
import com.google.inject.name.Named;

/**
 * The default authenticator.
 * <p/>
 * Builds the default token request with the following claims: iss,scope,aud,iat,exp.
 * <p/>
 * TODO scopes etc should come from the REST method and not from a global property
 */
public final class BuildTokenRequest implements Function<GeneratedHttpRequest, TokenRequest> {
   private final String assertionTargetDescription;
   private final String signatureAlgorithm;
   private final Supplier<OAuthCredentials> credentialsSupplier;
   private final long tokenDuration;

   @Inject(optional = true)
   @Named(ADDITIONAL_CLAIMS)
   private Map<String, String> additionalClaims = Collections.emptyMap();

   @Inject(optional = true)
   @Named(SCOPES)
   private String globalScopes = null;

   // injectable so expect tests can override with a predictable value
   @Inject(optional = true)
   private Supplier<Long> timeSourceMillisSinceEpoch = new Supplier<Long>() {
      @Override
      public Long get() {
         return System.currentTimeMillis();
      }
   };

   @Inject BuildTokenRequest(@Named(AUDIENCE) String assertionTargetDescription,
                            @Named(SIGNATURE_OR_MAC_ALGORITHM) String signatureAlgorithm,
                            Supplier<OAuthCredentials> credentialsSupplier,
                            @Named(Constants.PROPERTY_SESSION_INTERVAL) long tokenDuration) {
      this.assertionTargetDescription = assertionTargetDescription;
      this.signatureAlgorithm = signatureAlgorithm;
      this.credentialsSupplier = credentialsSupplier;
      this.tokenDuration = tokenDuration;
   }

   @Override public TokenRequest apply(GeneratedHttpRequest request) {
      long now = timeSourceMillisSinceEpoch.get() / 1000;

      // fetch the token
      Header header = Header.create(signatureAlgorithm, "JWT");

      Map<String, Object> claims = new LinkedHashMap<String, Object>();
      claims.put("iss", credentialsSupplier.get().identity);
      claims.put("scope", getOAuthScopes(request));
      claims.put("aud", assertionTargetDescription);
      claims.put(EXPIRATION_TIME, now + tokenDuration);
      claims.put(ISSUED_AT, now);
      claims.putAll(additionalClaims);

      return TokenRequest.create(header, claims);
   }

   private String getOAuthScopes(GeneratedHttpRequest request) {
      Invokable<?, ?> invokable = request.getInvocation().getInvokable();
      
      OAuthScopes classScopes = invokable.getOwnerType().getRawType().getAnnotation(OAuthScopes.class);
      OAuthScopes methodScopes = invokable.getAnnotation(OAuthScopes.class);

      // if no annotations are present the rely on globally set scopes
      if (classScopes == null && methodScopes == null) {
         checkState(globalScopes != null, String.format("REST class or method should be annotated " +
                 "with OAuthScopes specifying required permissions. Alternatively a global property " +
                 "\"oauth.scopes\" may be set to define scopes globally. REST Class: %s, Method: %s",
                 invokable.getOwnerType(),
                 invokable.getName()));
         return globalScopes;
      }

      OAuthScopes scopes = methodScopes != null ? methodScopes : classScopes;
      return Joiner.on(",").join(scopes.value());
   }
}
