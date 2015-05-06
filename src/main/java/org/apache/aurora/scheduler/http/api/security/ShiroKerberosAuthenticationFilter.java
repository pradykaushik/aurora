/**
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.aurora.scheduler.http.api.security;

import java.io.IOException;
import java.util.logging.Logger;

import javax.inject.Inject;
import javax.inject.Provider;
import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.ws.rs.core.HttpHeaders;

import com.google.common.base.Optional;

import org.apache.aurora.scheduler.http.AbstractFilter;
import org.apache.shiro.authc.AuthenticationException;
import org.apache.shiro.authz.UnauthenticatedException;
import org.apache.shiro.subject.Subject;

import static java.util.Objects.requireNonNull;

public class ShiroKerberosAuthenticationFilter extends AbstractFilter {
  private static final Logger LOG =
      Logger.getLogger(ShiroKerberosAuthenticationFilter.class.getName());

  /**
   * From http://tools.ietf.org/html/rfc4559.
   */
  public static final String NEGOTIATE = "Negotiate";

  private final Provider<Subject> subjectProvider;

  @Inject
  ShiroKerberosAuthenticationFilter(Provider<Subject> subjectProvider) {
    this.subjectProvider = requireNonNull(subjectProvider);
  }

  @Override
  protected void doFilter(
      HttpServletRequest request,
      HttpServletResponse response,
      FilterChain chain) throws IOException, ServletException {

    Optional<String> authorizationHeaderValue =
        Optional.fromNullable(request.getHeader(HttpHeaders.AUTHORIZATION));
    if (authorizationHeaderValue.isPresent()) {
      LOG.fine("Authorization header is present");
      AuthorizeHeaderToken token;
      try {
        token = new AuthorizeHeaderToken(authorizationHeaderValue.get());
      } catch (IllegalArgumentException e) {
        LOG.info("Malformed Authorize header: " + e.getMessage());
        response.sendError(HttpServletResponse.SC_BAD_REQUEST);
        return;
      }
      try {
        subjectProvider.get().login(token);
        chain.doFilter(request, response);
      } catch (AuthenticationException e) {
        LOG.warning("Login failed: " + e.getMessage());
        sendChallenge(response);
      }
    } else {
      // Incoming request is unauthenticated, but some RPCs might be okay with that.
      try {
        chain.doFilter(request, response);
      } catch (UnauthenticatedException e) {
        sendChallenge(response);
      }
    }
  }

  private void sendChallenge(HttpServletResponse response) throws IOException {
    response.setHeader(HttpHeaders.WWW_AUTHENTICATE, NEGOTIATE);
    response.sendError(HttpServletResponse.SC_UNAUTHORIZED);
  }
}