/*
 * (C) Copyright 2018-2019 Webdrone SAS (https://www.webdrone.fr) and contributors.
 * (C) Copyright 2015-2016 Opencell SAS (http://opencellsoft.com/) and contributors.
 * (C) Copyright 2009-2014 Manaty SARL (http://manaty.net/) and contributors.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  
 * This program is not suitable for any direct or indirect application in MILITARY industry
 * See the GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.meveo.service.script;

import org.meveo.admin.exception.BusinessException;
import org.meveo.admin.exception.ElementNotFoundException;
import org.meveo.admin.exception.InvalidScriptException;
import org.meveo.admin.util.ResourceBundle;
import org.meveo.cache.CacheKeyStr;
import org.meveo.commons.utils.FileUtils;
import org.meveo.commons.utils.StringUtils;
import org.meveo.model.scripts.CustomScript;
import org.meveo.model.scripts.ScriptInstanceError;
import org.meveo.model.scripts.ScriptSourceTypeEnum;

import javax.ejb.Lock;
import javax.ejb.LockType;
import javax.inject.Inject;
import javax.persistence.NoResultException;
import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaFileObject;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.TypeVariable;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public abstract class CustomScriptService<T extends CustomScript, SI extends ScriptInterface> extends ExecutableService<T, SI> {

    @Inject
    private ResourceBundle resourceMessages;

    protected final Class<SI> scriptInterfaceClass;

    private Map<CacheKeyStr, Class<SI>> allScriptInterfaces = new HashMap<>();

    private CharSequenceCompiler<SI> compiler;

    private String classpath = "";

    /**
     * Constructor.
     */
    @SuppressWarnings({ "unchecked", "rawtypes" })
    public CustomScriptService() {
        super();
        Class clazz = getClass();
        while (!(clazz.getGenericSuperclass() instanceof ParameterizedType)) {
            clazz = clazz.getSuperclass();
        }
        Object o = ((ParameterizedType) clazz.getGenericSuperclass()).getActualTypeArguments()[1];

        if (o instanceof TypeVariable) {
            this.scriptInterfaceClass = (Class<SI>) ((TypeVariable) o).getBounds()[0];
        } else {
            this.scriptInterfaceClass = (Class<SI>) o;
        }
    }

    /**
     * Find scripts by source type.
     * 
     * @param type script source type
     * @return list of scripts
     */
    @SuppressWarnings("unchecked")
    protected List<T> findByType(ScriptSourceTypeEnum type) {
        List<T> result = new ArrayList<T>();
        try {
            result = (List<T>) getEntityManager().createNamedQuery("CustomScript.getScriptInstanceByTypeActive").setParameter("sourceTypeEnum", type).getResultList();
        } catch (NoResultException e) {

        }
        return result;
    }

    @Override
    protected void afterUpdateOrCreate(T script) {
        compileScript(script, false);
    }

    @Override
    protected void validate(T script) throws BusinessException {
        String className = getClassName(script.getScript());
        if (className == null) {
            throw new BusinessException(resourceMessages.getString("message.scriptInstance.sourceInvalid"));
        }
        String fullClassName = getFullClassname(script.getScript());
        if (isOverwritesJavaClass(fullClassName)) {
            throw new BusinessException(resourceMessages.getString("message.scriptInstance.classInvalid", fullClassName));
        }
    }

    @Override
    protected String getCode(T script) {
        return getFullClassname(script.getScript());
    }

    @Override
    public SI getExecutionEngine(String scriptCode) {
        try {
            return this.getScriptInstance(scriptCode);
        } catch (ElementNotFoundException | InvalidScriptException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Check full class name is existed class path or not.
     * 
     * @param fullClassName full class name
     * @return true i class is overridden
     */
    public static boolean isOverwritesJavaClass(String fullClassName) {
        try {
            Class.forName(fullClassName);
            return true;
        } catch (ClassNotFoundException ex) {
            return false;
        }
    }

    /**
     * Construct classpath for script compilation
     * 
     * @throws IOException
     */
    private void constructClassPath() throws IOException {

        if (classpath.length() == 0) {

            // Check if deploying an exploded archive or a compressed file
            String thisClassfile = this.getClass().getProtectionDomain().getCodeSource().getLocation().getFile();

            File realFile = new File(thisClassfile);

            // Was deployed as exploded archive
            if (realFile.exists()) {
                File deploymentDir = realFile.getParentFile();
                for (File file : deploymentDir.listFiles()) {
                    if (file.getName().endsWith(".jar")) {
                        classpath += file.getCanonicalPath() + File.pathSeparator;
                    }
                }

                // War was deployed as compressed archive
            } else {

                org.jboss.vfs.VirtualFile vFile = org.jboss.vfs.VFS.getChild(thisClassfile);
                realFile = new File(org.jboss.vfs.VFSUtils.getPhysicalURI(vFile).getPath());

                File deploymentDir = realFile.getParentFile().getParentFile();

                for (File physicalLibDir : deploymentDir.listFiles()) {
                    if (physicalLibDir.isDirectory()) {
                        for (File f : FileUtils.getFilesToProcess(physicalLibDir, "*", "jar")) {
                            classpath += f.getCanonicalPath() + File.pathSeparator;
                        }
                    }
                }
            }
        }
        log.info("compileAll classpath={}", classpath);

    }

    /**
     * Build the classpath and compile all scripts.
     * 
     * @param scripts list of scripts
     */
    protected void compile(List<T> scripts) {
        try {

            constructClassPath();

            for (T script : scripts) {
                compileScript(script, false);
            }
        } catch (Exception e) {
            log.error("", e);
        }
    }

    /*
     * Compile a script
     */
    public void refreshCompiledScript(String scriptCode) {

        T script = findByCode(scriptCode);
        if (script == null) {
            clearCompiledScripts(scriptCode);
        } else {
            compileScript(script, false);
        }
        // detach(script);
    }

    /**
     * Compile script, a and update script entity status with compilation errors. Successfully compiled script is added to a compiled script cache if active and not in test
     * compilation mode.
     * 
     * @param script Script entity to compile
     * @param testCompile Is it a compilation for testing purpose. Won't clear nor overwrite existing compiled script cache.
     */
    public void compileScript(T script, boolean testCompile) {

        List<ScriptInstanceError> scriptErrors = compileScript(script.getCode(), script.getSourceTypeEnum(), script.getScript(), script.isActive(), testCompile);

        script.setError(scriptErrors != null && !scriptErrors.isEmpty());
        script.setScriptErrors(scriptErrors);
    }

    /**
     * Compile script. DOES NOT update script entity status. Successfully compiled script is added to a compiled script cache if active and not in test compilation mode.
     * 
     * @param scriptCode Script entity code
     * @param sourceType Source code language type
     * @param sourceCode Source code
     * @param isActive Is script active. It will compile it anyway. Will clear but not overwrite existing compiled script cache.
     * @param testCompile Is it a compilation for testing purpose. Won't clear nor overwrite existing compiled script cache.
     * 
     * @return A list of compilation errors if not compiled
     */
    private List<ScriptInstanceError> compileScript(String scriptCode, ScriptSourceTypeEnum sourceType, String sourceCode, boolean isActive, boolean testCompile) {

        log.debug("Compile script {}", scriptCode);

        try {
            if (!testCompile) {
                clearCompiledScripts(scriptCode);
            }

            // For now no need to check source type if (sourceType==ScriptSourceTypeEnum.JAVA){

            Class<SI> compiledScript = compileJavaSource(sourceCode);

            if (!testCompile && isActive) {

                allScriptInterfaces.put(new CacheKeyStr(currentUser.getProviderCode(), scriptCode), compiledScript);

                log.debug("Compiled script {} added to compiled interface map", scriptCode);
            }

            return null;

        } catch (CharSequenceCompilerException e) {
            log.error("Failed to compile script {}. Compilation errors:", scriptCode);

            List<ScriptInstanceError> scriptErrors = new ArrayList<>();

            List<Diagnostic<? extends JavaFileObject>> diagnosticList = e.getDiagnostics().getDiagnostics();
            for (Diagnostic<? extends JavaFileObject> diagnostic : diagnosticList) {
                if ("ERROR".equals(diagnostic.getKind().name())) {
                    ScriptInstanceError scriptInstanceError = new ScriptInstanceError();
                    scriptInstanceError.setMessage(diagnostic.getMessage(Locale.getDefault()));
                    scriptInstanceError.setLineNumber(diagnostic.getLineNumber());
                    scriptInstanceError.setColumnNumber(diagnostic.getColumnNumber());
                    scriptInstanceError.setSourceFile(diagnostic.getSource().toString());
                    // scriptInstanceError.setScript(scriptInstance);
                    scriptErrors.add(scriptInstanceError);
                    // scriptInstanceErrorService.create(scriptInstanceError, scriptInstance.getAuditable().getCreator());
                    log.warn("{} script {} location {}:{}: {}", diagnostic.getKind().name(), scriptCode, diagnostic.getLineNumber(), diagnostic.getColumnNumber(),
                        diagnostic.getMessage(Locale.getDefault()));
                }
            }
            return scriptErrors;

        } catch (Exception e) {
            log.error("Failed while compiling script", e);
            List<ScriptInstanceError> scriptErrors = new ArrayList<>();
            ScriptInstanceError scriptInstanceError = new ScriptInstanceError();
            scriptInstanceError.setMessage(e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
            scriptErrors.add(scriptInstanceError);

            return scriptErrors;
        }
    }

    /**
     * Compile java Source script
     * 
     * @param javaSrc Java source to compile
     * @return Compiled class instance
     * @throws CharSequenceCompilerException char sequence compiler exception.
     */
    protected Class<SI> compileJavaSource(String javaSrc) throws CharSequenceCompilerException {

        supplementClassPathWithMissingImports(javaSrc);

        String fullClassName = getFullClassname(javaSrc);

        log.trace("Compile JAVA script {} with classpath {}", fullClassName, classpath);

        compiler = new CharSequenceCompiler<SI>(this.getClass().getClassLoader(), Arrays.asList("-cp", classpath));
        final DiagnosticCollector<JavaFileObject> errs = new DiagnosticCollector<JavaFileObject>();
        Class<SI> compiledScript = compiler.compile(fullClassName, javaSrc, errs, scriptInterfaceClass);
        return compiledScript;
    }

    /**
     * Supplement classpath with classes needed for the particular script compilation. Solves issue when classes server as jboss modules are referenced in script. E.g.
     * prg.slf4j.Logger
     * 
     * @param javaSrc Java source to compile
     */
    @SuppressWarnings("rawtypes")
    private void supplementClassPathWithMissingImports(String javaSrc) {

        String regex = "import (.*?);";
        Pattern pattern = Pattern.compile(regex);
        Matcher matcher = pattern.matcher(javaSrc);
        while (matcher.find()) {
            String className = matcher.group(1);
            try {
                if ((!className.startsWith("java") || className.startsWith("javax.persistence")) && !className.startsWith("org.meveo")) {
                    Class clazz = Class.forName(className);
                    try {
                        String location = clazz.getProtectionDomain().getCodeSource().getLocation().getFile();
                        if (location.startsWith("file:")) {
                            location = location.substring(5);
                        }
                        if (location.endsWith("!/")) {
                            location = location.substring(0, location.length() - 2);
                        }

                        if (!classpath.contains(location)) {
                            classpath += File.pathSeparator + location;
                        }

                    } catch (Exception e) {
                        log.warn("Failed to find location for class {}", className);
                    }
                }
            } catch (Exception e) {
                log.warn("Failed to find location for class {}", className);
            }
        }

    }

    /**
     * Find the script class for a given script code
     * 
     * @param scriptCode Script code
     * @return Script interface Class
     * @throws InvalidScriptException Were not able to instantiate or compile a script
     * @throws ElementNotFoundException Script not found
     */
    @Lock(LockType.READ)
    public Class<SI> getScriptInterface(String scriptCode) throws ElementNotFoundException, InvalidScriptException {
        Class<SI> result = null;

        result = allScriptInterfaces.get(new CacheKeyStr(currentUser.getProviderCode(), scriptCode));

        if (result == null) {
            result = getScriptInterfaceWCompile(scriptCode);
        }

        log.debug("getScriptInterface scriptCode:{} -> {}", scriptCode, result);
        return result;
    }

    /**
     * Compile the script class for a given script code if it is not compile yet. NOTE: method is executed synchronously due to WRITE lock. DO NOT CHANGE IT, so there would be only
     * one attempt to compile a new script class
     * 
     * @param scriptCode Script code
     * @return Script interface Class
     * @throws InvalidScriptException Were not able to instantiate or compile a script
     * @throws ElementNotFoundException Script not found
     */
    @Lock(LockType.WRITE)
    protected Class<SI> getScriptInterfaceWCompile(String scriptCode) throws ElementNotFoundException, InvalidScriptException {
        Class<SI> result = null;

        result = allScriptInterfaces.get(new CacheKeyStr(currentUser.getProviderCode(), scriptCode));

        if (result == null) {
            T script = findByCode(scriptCode);
            if (script == null) {
                log.debug("ScriptInstance with {} does not exist", scriptCode);
                throw new ElementNotFoundException(scriptCode, getEntityClass().getName());
            }
            compileScript(script, false);
            if (script.isError()) {
                log.debug("ScriptInstance {} failed to compile. Errors: {}", scriptCode, script.getScriptErrors());
                throw new InvalidScriptException(scriptCode, getEntityClass().getName());
            }
            result = allScriptInterfaces.get(new CacheKeyStr(currentUser.getProviderCode(), scriptCode));
        }

        if (result == null) {
            log.debug("ScriptInstance with {} does not exist", scriptCode);
            throw new ElementNotFoundException(scriptCode, getEntityClass().getName());
        }

        return result;
    }

    /**
     * Get a compiled script class
     * 
     * @param scriptCode Script code
     * @return A compiled script class
     * @throws InvalidScriptException Were not able to instantiate or compile a script
     * @throws ElementNotFoundException Script not found
     */
    @Lock(LockType.READ)
    public SI getScriptInstance(String scriptCode) throws ElementNotFoundException, InvalidScriptException {
        Class<SI> scriptClass = getScriptInterface(scriptCode);

        try {
            SI script = scriptClass.newInstance();
            return script;

        } catch (InstantiationException | IllegalAccessException e) {
            log.error("Failed to instantiate script {}", scriptCode, e);
            throw new InvalidScriptException(scriptCode, getEntityClass().getName());
        }
    }


    /**
     * Find the package name in a source java text.
     * 
     * @param src Java source code
     * @return Package name
     */
    public static String getPackageName(String src) {
        return StringUtils.patternMacher("package (.*?);", src);
    }

    /**
     * Find the class name in a source java text
     * 
     * @param src Java source code
     * @return Class name
     */
    public static String getClassName(String src) {
        String className = StringUtils.patternMacher("public class (.*) extends", src);
        if (className == null) {
            className = StringUtils.patternMacher("public class (.*) implements", src);
        }
        return className != null ? className.trim() : null;
    }

    /**
     * Gets a full classname of a script by combining a package (if applicable) and a classname
     * 
     * @param script Java source code
     * @return Full classname
     */
    public static String getFullClassname(String script) {
        String packageName = getPackageName(script);
        String className = getClassName(script);
        return (packageName != null ? packageName.trim() + "." : "") + className;
    }

    /**
     * Remove compiled script, its logs and cached instances for given script code
     * 
     * @param scriptCode Script code
     */
    public void clearCompiledScripts(String scriptCode) {
        super.clear(scriptCode);
        allScriptInterfaces.remove(new CacheKeyStr(currentUser.getProviderCode(), scriptCode));
    }
}