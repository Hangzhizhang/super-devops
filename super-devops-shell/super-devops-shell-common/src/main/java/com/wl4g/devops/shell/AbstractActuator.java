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
package com.wl4g.devops.shell;

import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Map.Entry;
import java.util.zip.CRC32;
import java.util.Optional;
import static java.lang.System.*;

import static org.apache.commons.lang3.StringUtils.*;

import static com.wl4g.devops.shell.utils.Types.*;
import static com.wl4g.devops.shell.utils.Reflections.*;
import com.wl4g.devops.shell.annotation.ShellOption;
import com.wl4g.devops.shell.config.AbstractConfiguration;
import com.wl4g.devops.shell.registry.ShellBeanRegistry;
import com.wl4g.devops.shell.registry.TargetMethodWrapper;
import com.wl4g.devops.shell.registry.TargetMethodWrapper.TargetParameter;
import com.wl4g.devops.shell.utils.Assert;
import com.wl4g.devops.shell.utils.LineUtils;

/**
 * Abstract shell component actuator
 * 
 * @author Wangl.sir <983708408@qq.com>
 * @version v1.0 2019年4月14日
 * @since
 */
public abstract class AbstractActuator implements Actuator {

	/**
	 * Enable shell console debug.
	 */
	final public static boolean DEBUG = getProperty("xdebug") != null;

	/**
	 * Shell handler bean registry
	 */
	final protected ShellBeanRegistry registry;

	/**
	 * Shell configuration
	 */
	final protected AbstractConfiguration config;

	public AbstractActuator(AbstractConfiguration config, ShellBeanRegistry registry) {
		Assert.notNull(registry, "Registry must not be null");
		Assert.notNull(config, "Registry must not be null");
		this.registry = registry;
		this.config = config;
	}

	@Override
	public Object process(String line) {
		if (isEmpty(line)) {
			return null;
		}

		// Invocation and result processing
		Object result = doProcess(resolveCommands(line));

		// Post processing result
		postProcessResult(result);

		return result;
	}

	/**
	 * Commands processing
	 * 
	 * @param commands
	 * @return
	 * @throws Exception
	 */
	protected Object doProcess(List<String> commands) {
		Assert.notNull(commands, "Console input commands must not be null");

		// Main argument option.(remove)
		String mainArg = commands.remove(0);

		// Target method wrap
		Assert.isTrue(registry.contains(mainArg), String.format("'%s': command not found", mainArg));
		TargetMethodWrapper tm = registry.getTargetMethods().get(mainArg);

		try {
			// Resolve method parameters
			List<Object> args = resolveArguments(commands, tm);

			// PreProcessing
			preProcessParameters(tm, args);

			// Invocation
			return tm.getMethod().invoke(tm.getTarget(), args.toArray());
		} catch (Exception e) {
			throw new IllegalStateException(e);
		}
	}

	/**
	 * Resolve arguments to method parameters
	 * 
	 * @param commands
	 * @param tm
	 * @return
	 * @throws IllegalAccessException
	 * @throws IllegalArgumentException
	 * @throws InstantiationException
	 * @throws Exception
	 */
	protected List<Object> resolveArguments(List<String> commands, TargetMethodWrapper tm)
			throws IllegalArgumentException, IllegalAccessException, InstantiationException {
		Assert.notNull(tm, "Error, Should targetMethodWrapper not be null?");

		/*
		 * Commands to javaBean map and validate protected. </br>
		 * (javaBean.fieldName or params.index(native type))->value
		 */
		final Map<String, String> beanMap = new HashMap<>();
		if (commands != null && !commands.isEmpty()) {
			for (int i = 0; i < commands.size() - 1; i++) {
				if (i % 2 == 0) {
					// Input opt
					String argname = commands.get(i);
					Assert.hasText(argname, String.format("Unable to get parameter name, i:%s", i));
					// Value(May be empty) See:[MARK3]
					String value = commands.get(i + 1);

					// Convert and save
					beanMap.put(convertIfNecessary(argname, tm), value);
				}
			}
		}

		// Method arguments
		List<Object> args = new ArrayList<>();

		for (TargetParameter parameter : tm.getParameters()) {
			// See: TargetMethodWrapper#initialize
			// To javaBean parameter
			if (!parameter.simpleType()) {
				Object paramBean = parameter.getParamType().newInstance();

				// Recursive full traversal De-serialization.
				doWithFullFields(paramBean, (attach, f, property) -> {
					// [MARK4],See:[ShellUtils.MARK0][TargetParameter.MARK1]
					return isSafetyModifier(f.getModifiers());
				}, (attach, f, property) -> {
					ShellOption shOpt = f.getDeclaredAnnotation(ShellOption.class);
					Assert.notNull(shOpt, "Error, Should shellOption not be null?");
					Object value = beanMap.get(f.getName());
					if (value == null) {
						value = shOpt.defaultValue();
					}

					// Validate argument(if required)
					if (shOpt.required() && !beanMap.containsKey(f.getName()) && isBlank(shOpt.defaultValue())) {
						throw new IllegalArgumentException(
								String.format("option: '-%s', '--%s' is required", shOpt.opt(), shOpt.lopt()));
					}
					value = convertToBaseOrSimpleSet((String) value, f.getType());
					f.setAccessible(true);
					f.set(attach, value);
				});

				args.add(paramBean);
			}
			// [MARK1]: To native parameter, See:[TargetParameter.MARK7]
			else {
				ShellOption shOpt = parameter.getShellOption();
				// Matching argument value
				Optional<Entry<String, String>> val = beanMap.entrySet().stream()
						.filter(arg -> equalsAny(arg.getKey(), shOpt.opt(), shOpt.lopt())).findFirst();

				// Default value
				String value = shOpt.defaultValue();
				if (val.isPresent()) {
					value = val.get().getValue();
				}

				// Validate argument(if required)
				if (shOpt.required() && !beanMap.containsKey(shOpt.opt()) && !beanMap.containsKey(shOpt.lopt())
						&& isBlank(shOpt.defaultValue())) {
					throw new IllegalArgumentException(
							String.format("option: '-%s', '--%s' is required", shOpt.opt(), shOpt.lopt()));
				}
				args.add(convertToBaseOrSimpleSet(value, parameter.getParamType()));
			}

		}

		return args;
	}

	/**
	 * Preprocess parameters.
	 * 
	 * @param tm
	 * @param args
	 */
	protected void preProcessParameters(TargetMethodWrapper tm, List<Object> args) {

	}

	/**
	 * Post invocation result processing.
	 * 
	 * @param result
	 */
	protected void postProcessResult(Object result) {

	}

	/**
	 * Convert argument to java bean actual param field name
	 * 
	 * @param argname
	 * @param tm
	 * @return java bean actual param field name or index(if native type)
	 */
	protected String convertIfNecessary(String argname, TargetMethodWrapper tm) {
		return tm.getSureParamName(LineUtils.clean(argname));
	}

	/**
	 * Resolve source commands
	 * 
	 * @param args
	 * @return
	 */
	protected List<String> resolveCommands(String line) {
		List<String> commands = LineUtils.parse(line);
		Assert.notEmpty(commands, "Commands must not be empty");
		return commands;
	}

	/**
	 * Ensure resolve server listen port.
	 * 
	 * @param appName
	 * @return
	 */
	protected int ensureDetermineServPort(String appName) {
		Assert.hasLength(appName, "appName must not be empty");
		String origin = trimToEmpty(appName).toUpperCase(Locale.ENGLISH);

		CRC32 crc32 = new CRC32();
		crc32.update(origin.getBytes(Charset.forName("UTF-8")));
		int mod = config.getEndPort() - config.getBeginPort();
		int servport = (int) (config.getBeginPort() + (crc32.getValue() % mod & (mod - 1)));

		if (DEBUG) {
			out.println(String.format("Shell servports (%s ~ %s), origin(%s), sign(%s), determine(%s)", config.getBeginPort(),
					config.getEndPort(), origin, crc32.getValue(), servport));
		}
		return servport;
	}

}