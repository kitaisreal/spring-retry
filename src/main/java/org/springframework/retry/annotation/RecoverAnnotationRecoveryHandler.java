/*
 * Copyright 2013-2019 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.retry.annotation;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;

import org.springframework.classify.SubclassClassifier;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.retry.ExhaustedRetryException;
import org.springframework.retry.interceptor.MethodInvocationRecoverer;
import org.springframework.util.ReflectionUtils;
import org.springframework.util.ReflectionUtils.MethodCallback;

/**
 * A recoverer for method invocations based on the <code>@Recover</code> annotation. A
 * suitable recovery method is one with a Throwable type as the first parameter and the
 * same return type and arguments as the method that failed. The Throwable first argument
 * is optional and if omitted the method is treated as a default (called when there are no
 * other matches). Generally the best matching method is chosen based on the type of the
 * first parameter and the type of the exception being handled. The closest match in the
 * class hierarchy is chosen, so for instance if an IllegalArgumentException is being
 * handled and there is a method whose first argument is RuntimeException, then it will be
 * preferred over a method whose first argument is Throwable.
 *
 * @author Dave Syer
 * @author Josh Long
 * @author Aldo Sinanaj
 * @author Randell Callahan
 * @author Nathanaël Roberts
 * @param <T> the type of the return value from the recovery
 */
public class RecoverAnnotationRecoveryHandler<T> implements MethodInvocationRecoverer<T> {

	private SubclassClassifier<Throwable, Method> classifier = new SubclassClassifier<Throwable, Method>();

	private Map<Method, SimpleMetadata> methods = new HashMap<Method, SimpleMetadata>();

	private Object target;

	private String recoverMethodName;

	public RecoverAnnotationRecoveryHandler(Object target, Method method) {
		this.target = target;
		init(target, method);
	}

	@Override
	public T recover(Object[] args, Throwable cause) {
		Method method = findClosestMatch(args, cause.getClass());
		if (method == null) {
			throw new ExhaustedRetryException("Cannot locate recovery method", cause);
		}
		SimpleMetadata meta = this.methods.get(method);
		Object[] argsToUse = meta.getArgs(cause, args);
		boolean methodAccessible = method.isAccessible();
		try {
			ReflectionUtils.makeAccessible(method);
			@SuppressWarnings("unchecked")
			T result = (T) ReflectionUtils.invokeMethod(method, this.target, argsToUse);
			return result;
		}
		finally {
			if (methodAccessible != method.isAccessible()) {
				method.setAccessible(methodAccessible);
			}
		}
	}

	private Method findClosestMatch(Object[] args, Class<? extends Throwable> cause) {
		Method result = null;

		boolean shouldUseRecoverMethodName = !this.recoverMethodName.equals("");
		if (shouldUseRecoverMethodName) {
			for (Method method : this.methods.keySet()) {
				SimpleMetadata meta = this.methods.get(method);
				if (meta.getName().equals(this.recoverMethodName) && meta.type.isAssignableFrom(cause)
						&& compareParameters(args, meta.getArgCount(), method.getParameterTypes())) {
					result = method;
					break;
				}
			}
		}
		else {
			int min = Integer.MAX_VALUE;
			for (Method method : this.methods.keySet()) {
				SimpleMetadata meta = this.methods.get(method);
				Class<? extends Throwable> type = meta.getType();
				if (type == null) {
					type = Throwable.class;
				}
				if (type.isAssignableFrom(cause)) {
					int distance = calculateDistance(cause, type);
					if (distance < min) {
						min = distance;
						result = method;
					}
					else if (distance == min) {
						boolean parametersMatch = compareParameters(args, meta.getArgCount(),
								method.getParameterTypes());
						if (parametersMatch) {
							result = method;
						}
					}
				}
			}
		}
		return result;
	}

	private int calculateDistance(Class<? extends Throwable> cause, Class<? extends Throwable> type) {
		int result = 0;
		Class<?> current = cause;
		while (current != type && current != Throwable.class) {
			result++;
			current = current.getSuperclass();
		}
		return result;
	}

	private boolean compareParameters(Object[] args, int argCount, Class<?>[] parameterTypes) {
		if (argCount == (args.length + 1)) {
			int startingIndex = 0;
			if (parameterTypes.length > 0 && Throwable.class.isAssignableFrom(parameterTypes[0])) {
				startingIndex = 1;
			}
			for (int i = startingIndex; i < parameterTypes.length; i++) {
				final Object argument = i - startingIndex < args.length ? args[i - startingIndex] : null;
				if (argument == null) {
					continue;
				}
				if (!parameterTypes[i].isAssignableFrom(argument.getClass())) {
					return false;
				}
			}
			return true;
		}
		return false;
	}

	private void init(Object target, Method method) {
		final Map<Class<? extends Throwable>, Method> types = new HashMap<Class<? extends Throwable>, Method>();
		final Method failingMethod = method;
		Retryable retryable = method.getAnnotation(Retryable.class);
		this.recoverMethodName = retryable != null ? retryable.recoverName() : "";
		ReflectionUtils.doWithMethods(failingMethod.getDeclaringClass(), new MethodCallback() {
			@Override
			public void doWith(Method method) throws IllegalArgumentException, IllegalAccessException {
				Recover recover = AnnotationUtils.findAnnotation(method, Recover.class);
				if (recover != null && method.getReturnType().isAssignableFrom(failingMethod.getReturnType())) {
					Class<?>[] parameterTypes = method.getParameterTypes();
					String functionName = !recover.name().equals("") ? recover.name() : method.getName();
					if (parameterTypes.length > 0 && Throwable.class.isAssignableFrom(parameterTypes[0])) {
						@SuppressWarnings("unchecked")
						Class<? extends Throwable> type = (Class<? extends Throwable>) parameterTypes[0];
						types.put(type, method);
						RecoverAnnotationRecoveryHandler.this.methods.put(method,
								new SimpleMetadata(parameterTypes.length, type, functionName));
					}
					else {
						RecoverAnnotationRecoveryHandler.this.classifier.setDefaultValue(method);
						RecoverAnnotationRecoveryHandler.this.methods.put(method,
								new SimpleMetadata(parameterTypes.length, null, functionName));
					}
				}
			}
		});
		this.classifier.setTypeMap(types);
		optionallyFilterMethodsBy(failingMethod.getReturnType());
	}

	private void optionallyFilterMethodsBy(Class<?> returnClass) {
		Map<Method, SimpleMetadata> filteredMethods = new HashMap<Method, SimpleMetadata>();
		for (Method method : this.methods.keySet()) {
			if (method.getReturnType() == returnClass) {
				filteredMethods.put(method, this.methods.get(method));
			}
		}
		if (filteredMethods.size() > 0) {
			this.methods = filteredMethods;
		}
	}

	private static class SimpleMetadata {

		private int argCount;

		private Class<? extends Throwable> type;

		private String name;

		public SimpleMetadata(int argCount, Class<? extends Throwable> type, String name) {
			super();
			this.argCount = argCount;
			this.type = type;
			this.name = name;
		}

		public int getArgCount() {
			return this.argCount;
		}

		public String getName() {
			return this.name;
		}

		public Class<? extends Throwable> getType() {
			return this.type;
		}

		public Object[] getArgs(Throwable t, Object[] args) {
			Object[] result = new Object[getArgCount()];
			int startArgs = 0;
			if (this.type != null) {
				result[0] = t;
				startArgs = 1;
			}
			int length = result.length - startArgs > args.length ? args.length : result.length - startArgs;
			if (length == 0) {
				return result;
			}
			System.arraycopy(args, 0, result, startArgs, length);
			return result;
		}

	}

}
