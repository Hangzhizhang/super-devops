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
package com.wl4g.devops.iam.sns.handler;

import java.util.Collections;
import java.util.Map;

import javax.servlet.http.HttpServletRequest;

import org.apache.shiro.web.util.WebUtils;
import org.springframework.util.Assert;

import com.wl4g.devops.iam.common.config.AbstractIamProperties.Which;
import com.wl4g.devops.iam.config.IamProperties;
import com.wl4g.devops.iam.config.SnsProperties;
import com.wl4g.devops.iam.context.ServerSecurityContext;
import com.wl4g.devops.iam.sns.SocialConnectionFactory;

/**
 * Client authc SNS handler.(e.g:WeChat official platform account)
 * 
 * @author Wangl.sir <983708408@qq.com>
 * @version v1.0 2019年2月24日
 * @since
 */
public class ClientAuthcSnsHandler extends AbstractSnsHandler {

	public ClientAuthcSnsHandler(IamProperties config, SnsProperties snsConfig, SocialConnectionFactory connectFactory,
			ServerSecurityContext context) {
		super(config, snsConfig, connectFactory, context);
	}

	@Override
	protected Map<String, String> getAuthorizeUrlQueryParams(Which which, String provider, String state,
			Map<String, String> connectParams) {
		Map<String, String> queryParams = super.getAuthorizeUrlQueryParams(which, provider, state, connectParams);
		/*
		 * For redirect login needs,
		 * <br/><br/>see:i.f.AbstractIamAuthenticationFilter#onLoginSuccess()
		 * <br/><br/>grantTicket by xx.i.h.AuthenticationHandler#loggedin()
		 */
		String appKey = this.config.getParam().getApplication();
		queryParams.put(appKey, connectParams.get(appKey));
		return queryParams;
	}

	@Override
	protected void checkConnectRequireds(String provider, String state, Map<String, String> connectParams) {
		super.checkConnectRequireds(provider, state, connectParams);

		// Check application
		Assert.hasText(connectParams.get(config.getParam().getApplication()),
				String.format("'%s' must not be empty", config.getParam().getApplication()));
	}

	@Override
	protected Map<String, String> getOauth2ConnectParameters(String state, HttpServletRequest request) {
		return Collections.singletonMap(config.getParam().getApplication(),
				WebUtils.getCleanParam(request, config.getParam().getApplication()));
	}

	@Override
	protected String buildResponseMessage(String provider, String callbackId, Map<String, String> connectParams,
			HttpServletRequest request) {
		String appKey = this.config.getParam().getApplication();
		return new StringBuffer(getLoginSubmissionUrl(provider, callbackId, request)).append("&").append(appKey).append("=")
				.append(connectParams.get(appKey)).toString();
	}

	@Override
	public Which whichType() {
		return Which.CLIENT_AUTH;
	}

}