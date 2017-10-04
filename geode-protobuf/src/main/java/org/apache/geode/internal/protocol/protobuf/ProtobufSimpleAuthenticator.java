/*
 * Licensed to the Apache Software Foundation (ASF) under one or more contributor license
 * agreements. See the NOTICE file distributed with this work for additional information regarding
 * copyright ownership. The ASF licenses this file to You under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance with the License. You may obtain a
 * copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */
package org.apache.geode.internal.protocol.protobuf;

import org.apache.geode.internal.security.SecurityService;
import org.apache.geode.management.internal.security.ResourceConstants;
import org.apache.geode.security.AuthenticationRequiredException;
import org.apache.geode.security.server.Authenticator;
import org.apache.geode.security.AuthenticationFailedException;
import org.apache.geode.security.SecurityManager;
import org.apache.geode.security.server.Authorizer;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Map;
import java.util.Properties;

import org.apache.shiro.subject.Subject;

public class ProtobufSimpleAuthenticator implements Authenticator {
  private ProtobufSimpleAuthorizer authorizer = null;

  @Override
  public void authenticate(InputStream inputStream, OutputStream outputStream,
      SecurityService securityService) throws IOException {
    AuthenticationAPI.SimpleAuthenticationRequest authenticationRequest =
        AuthenticationAPI.SimpleAuthenticationRequest.parseDelimitedFrom(inputStream);
    if (authenticationRequest == null) {
      throw new EOFException();
    }

    Properties properties = new Properties();
    properties.putAll(authenticationRequest.getCredentialsMap());

    authorizer = null; // authenticating a new user clears current authorizer
    try {
      Subject subject = securityService.login(properties);
      if (subject != null) {
        authorizer = new ProtobufSimpleAuthorizer(subject, securityService);
      }
    } catch (AuthenticationFailedException e) {
      authorizer = null;
    }

    AuthenticationAPI.SimpleAuthenticationResponse.newBuilder().setAuthenticated(isAuthenticated())
        .build().writeDelimitedTo(outputStream);
  }

  @Override
  public boolean isAuthenticated() {
    // note: an authorizer is only created if the user has been authenticated
    return authorizer != null;
  }

  @Override
  public Authorizer getAuthorizer() throws AuthenticationRequiredException {
    if (authorizer == null) {
      throw new AuthenticationRequiredException("Not yet authenticated");
    }
    return authorizer;
  }

  @Override
  public String implementationID() {
    return "SIMPLE";
  }
}
