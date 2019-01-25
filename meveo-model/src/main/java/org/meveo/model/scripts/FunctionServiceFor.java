package org.meveo.model.scripts;

import static java.lang.annotation.ElementType.TYPE;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

import java.lang.annotation.Retention;
import java.lang.annotation.Target;

import javax.inject.Qualifier;

@Qualifier
@Retention(RUNTIME)
@Target(TYPE)
public @interface FunctionServiceFor {

	Class<? extends Function> value();
	
}
