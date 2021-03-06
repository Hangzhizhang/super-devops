/*
 * Copyright 2017 ~ 2025 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.wl4g.devops.iam.client.realm;

import org.apache.shiro.SecurityUtils;
import org.apache.shiro.authc.AuthenticationException;
import org.apache.shiro.authc.AuthenticationInfo;
import org.apache.shiro.authc.AuthenticationToken;
import org.apache.shiro.authc.CredentialsException;
import org.apache.shiro.authc.SimpleAuthenticationInfo;
import org.apache.shiro.authz.AuthorizationInfo;
import org.apache.shiro.authz.SimpleAuthorizationInfo;
import org.apache.shiro.realm.AuthorizingRealm;
import org.apache.shiro.subject.PrincipalCollection;
import org.apache.shiro.subject.SimplePrincipalCollection;
import org.apache.shiro.util.Assert;
import org.apache.shiro.util.CollectionUtils;
import org.springframework.util.StringUtils;

import com.wl4g.devops.common.bean.iam.model.TicketAssertion;
import com.wl4g.devops.common.bean.iam.model.TicketAssertion.IamPrincipal;
import com.wl4g.devops.common.bean.iam.model.TicketValidationModel;
import com.wl4g.devops.common.exception.iam.TicketValidateException;
import com.wl4g.devops.iam.client.authc.FastCasAuthenticationToken;
import com.wl4g.devops.iam.client.config.IamClientProperties;
import com.wl4g.devops.iam.client.validation.IamValidator;

import static com.wl4g.devops.common.constants.IAMDevOpsConstants.KEY_REMEMBERME_NAME;
import static com.wl4g.devops.common.constants.IAMDevOpsConstants.KEY_ROLE_ATTRIBUTE_NAME;
import static com.wl4g.devops.common.constants.IAMDevOpsConstants.KEY_PERMIT_ATTRIBUTE_NAME;

import java.util.Date;
import java.util.List;
import java.util.Map;

/**
 * This realm implementation acts as a CAS client to a CAS server for
 * authentication and basic authorization.
 * <p/>
 * This realm functions by inspecting a submitted
 * {@link org.apache.shiro.cas.CasToken CasToken} (which essentially wraps a CAS
 * service ticket) and validates it against the CAS server using a configured
 * CAS {@link org.jasig.cas.client.validation.TicketValidator TicketValidator}.
 * <p/>
 * The {@link #getValidationProtocol() validationProtocol} is {@code CAS} by
 * default, which indicates that a a
 * {@link org.jasig.cas.client.validation.Cas20ServiceTicketValidator
 * Cas20ServiceTicketValidator} will be used for ticket validation. You can
 * alternatively set or
 * {@link org.jasig.cas.client.validation.Saml11TicketValidator
 * Saml11TicketValidator} of CAS client. It is based on {@link AuthorizingRealm
 * AuthorizingRealm} for both authentication and authorization. User id and
 * attributes are retrieved from the CAS service ticket validation response
 * during authentication phase. Roles and permissions are computed during
 * authorization phase (according to the attributes previously retrieved).
 *
 * @since 1.2
 */
public class FastCasAuthorizingRealm extends AbstractAuthorizingRealm {

	public FastCasAuthorizingRealm(IamClientProperties config, IamValidator<TicketValidationModel, TicketAssertion> validator) {
		super(config, validator);
		super.setAuthenticationTokenClass(FastCasAuthenticationToken.class);
	}

	/**
	 * Authenticates a user and retrieves its information.
	 * 
	 * @param token
	 *            the authentication token
	 * @throws AuthenticationException
	 *             if there is an error during authentication.
	 */
	@Override
	protected AuthenticationInfo doGetAuthenticationInfo(AuthenticationToken token) throws AuthenticationException {
		String granticket = "";
		try {
			Assert.notNull(token, "'authenticationToken' must not be null");
			FastCasAuthenticationToken fcToken = (FastCasAuthenticationToken) token;

			// Get request flash grant ticket(May be empty)
			granticket = (String) fcToken.getCredentials();

			// Contact CAS remote server to validate ticket
			TicketAssertion assertion = this.doRequestRemoteTicketValidation(granticket);

			// Assert ticket validate.
			this.assertTicketValidation(assertion);

			// Update settings grant ticket
			String newGrantTicket = String.valueOf(assertion.getAttributes().get(config.getParam().getGrantTicket()));
			fcToken.setCredentials(newGrantTicket);

			/*
			 * Synchronize with xx.xx..mgt.JedisIamSessionDAO#update <br/>
			 * Update session expire date time
			 */
			Date validUntilDate = assertion.getValidUntilDate();
			long maxIdleTimeMs = validUntilDate.getTime() - System.currentTimeMillis();
			Assert.state(maxIdleTimeMs > 0,
					String.format("Remote authenticated response session expired time:[%s] invalid, maxIdleTimeMs:[%s]",
							validUntilDate, maxIdleTimeMs));
			SecurityUtils.getSubject().getSession().setTimeout(maxIdleTimeMs);

			// Get principal, user id and attributes
			IamPrincipal principal = assertion.getPrincipal();
			String loginId = principal.getName();
			if (log.isInfoEnabled()) {
				log.info("Validated grantTicket[{}] and loginId[{}]", granticket, loginId);
			}

			// Refresh authentication token (userId + rememberMe)
			Map<String, Object> principalMap = principal.getAttributes();
			fcToken.setPrincipal(loginId);
			String rememberMe = (String) principalMap.get(KEY_REMEMBERME_NAME);
			fcToken.setRememberMe(Boolean.parseBoolean(rememberMe));

			// Create simple-authentication info
			List<Object> principals = CollectionUtils.asList(loginId, principalMap);
			PrincipalCollection principalCollection = new SimplePrincipalCollection(principals, super.getName());

			// You should always use token credentials because the default
			// SimpleCredentialsMatcher checks
			return new SimpleAuthenticationInfo(principalCollection, fcToken.getCredentials());
		} catch (Exception e) {
			throw new CredentialsException(String.format("Unable to validate ticket [%s]", granticket), e);
		}
	}

	/**
	 * Retrieves the AuthorizationInfo for the given principals (the CAS
	 * previously authenticated user : id + attributes).
	 * 
	 * @param principals
	 *            the primary identifying principals of the AuthorizationInfo
	 *            that should be retrieved.
	 * @return the AuthorizationInfo associated with this principals.
	 */
	@Override
	@SuppressWarnings("unchecked")
	protected AuthorizationInfo doGetAuthorizationInfo(PrincipalCollection principals) {
		// retrieve user information
		SimplePrincipalCollection principalCollection = (SimplePrincipalCollection) principals;
		List<Object> listPrincipals = principalCollection.asList();
		Map<String, String> attributes = (Map<String, String>) listPrincipals.get(1);

		// Create simple authorization info
		SimpleAuthorizationInfo info = new SimpleAuthorizationInfo();

		// Get roles from attributes
		String roles = attributes.get(KEY_ROLE_ATTRIBUTE_NAME);
		super.addRoles(info, super.split(roles));

		// Get permissions from attributes
		String permits = attributes.get(KEY_PERMIT_ATTRIBUTE_NAME);
		super.addPermissions(info, super.split(permits));

		return info;
	}

	/**
	 * Contact fast-CAS remote server to validate ticket.
	 * 
	 * @param ticket
	 * @return
	 */
	private TicketAssertion doRequestRemoteTicketValidation(String ticket) {
		return this.ticketValidator.validate(new TicketValidationModel(ticket, config.getServiceName()));
	}

	/**
	 * Assert ticket validate failure
	 * 
	 * @param assertion
	 * @throws TicketValidateException
	 */
	private void assertTicketValidation(TicketAssertion assertion) throws TicketValidateException {
		if (assertion == null) {
			throw new TicketValidateException("ticket assertion must not be null");
		}
		if (assertion.getAttributes().get(config.getParam().getGrantTicket()) == null) {
			throw new TicketValidateException("grant ticket must not be null");
		}
		IamPrincipal principal = assertion.getPrincipal();
		if (principal == null) {
			throw new TicketValidateException("'principal' must not be null");
		}
		if (principal.getAttributes() == null || principal.getAttributes().isEmpty()) {
			throw new TicketValidateException("'principal.attributes' must not be empty");
		}
		if (!StringUtils.hasText((String) principal.getAttributes().get(KEY_ROLE_ATTRIBUTE_NAME))) {
			if (log.isWarnEnabled()) {
				log.warn("Principal '{}' role is empty", principal.getName());
			}
			// throw new TicketValidationException(String.format("Principal '%s'
			// roles must not empty", principal.getName()));
		}
		if (!StringUtils.hasText((String) principal.getAttributes().get(KEY_PERMIT_ATTRIBUTE_NAME))) {
			if (log.isWarnEnabled()) {
				log.warn("Principal '{}' permits is empty", principal.getName());
			}
			// throw new TicketValidationException(String.format("Principal '%s'
			// permits must not empty", principal.getName()));
		}
	}

}