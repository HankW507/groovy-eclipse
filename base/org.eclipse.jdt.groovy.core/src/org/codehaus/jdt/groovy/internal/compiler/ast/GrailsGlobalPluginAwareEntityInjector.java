package org.codehaus.jdt.groovy.internal.compiler.ast;

import groovy.lang.GroovyClassLoader;

import java.util.List;

import org.codehaus.groovy.ast.AnnotationNode;
import org.codehaus.groovy.ast.ClassNode;
import org.codehaus.groovy.ast.expr.ConstantExpression;
import org.codehaus.groovy.classgen.GeneratorContext;
import org.codehaus.groovy.control.CompilationFailedException;
import org.codehaus.groovy.control.CompilationUnit.PrimaryClassNodeOperation;
import org.codehaus.groovy.control.SourceUnit;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.Path;
import org.eclipse.jdt.internal.core.builder.SourceFile;

/**
 * Grails runs an ASTTransform org.codehaus.groovy.grails.compiler.injection.GlobalPluginAwareEntityASTTransformation. But this
 * transform doesn't execute when compiler is called from within STS/Eclipse because it requires plugin information inside of
 * GrailsBuildSettings to be intialized and present in BuildSettingsHolder. All of this is finicky and fragile to setup. So instead, 
 * this somewhat hacky workaround in the Groovy Eclipse compiler does the same thing as the tranforms.
 * 
 * @author Kris De Volder
 */
@SuppressWarnings("restriction")
public class GrailsGlobalPluginAwareEntityInjector extends PrimaryClassNodeOperation {

	private static final boolean DEBUG = false;

	private static void debug(String msg) {
		System.out.println(msg);
	}

	private static class PluginInfo {

		final String name;
		final String version;

		public PluginInfo(String name, String version) {
			this.name = name;
			this.version = version;
		}

		@Override
		public String toString() {
			return "Plugin(name=" + name + ", version=" + version + ")";
		}

	}

	private GroovyClassLoader groovyClassLoader;

	// If true then some part of injector has broken down so avoid trying again
	private boolean broken = false;

	public GrailsGlobalPluginAwareEntityInjector(GroovyClassLoader groovyClassLoader) {
		this.groovyClassLoader = groovyClassLoader;
	}

	@Override
	public void call(SourceUnit sourceUnit, GeneratorContext context, ClassNode classNode) throws CompilationFailedException {
		if (broken) {
			return;
		}
		try {
			String sourcePathString = sourceUnit.getName();
			IPath sourcePath = new Path(sourcePathString);
			PluginInfo info = getInfo(sourcePath);
			if (info != null) {
				if (DEBUG) {
					debug("APPLY transform: " + sourcePath);
				}

				// The transform should be applied. (code below lifted from
				// org.codehaus.groovy.grails.compiler.injection.GlobalPluginAwareEntityASTTransformation)
				Class<?> GrailsPlugin_class = Class.forName("org.codehaus.groovy.grails.plugins.metadata.GrailsPlugin", false,
						groovyClassLoader);

				final ClassNode annotation = new ClassNode(GrailsPlugin_class);
				final List<?> list = classNode.getAnnotations(annotation);
				if (!list.isEmpty()) {
					return;
				}

				final AnnotationNode annotationNode = new AnnotationNode(annotation);
				annotationNode.addMember("name", new ConstantExpression(info.name));
				annotationNode.addMember("version", new ConstantExpression(info.version));
				annotationNode.setRuntimeRetention(true);
				annotationNode.setClassRetention(true);

				classNode.addAnnotation(annotationNode);
			} else {
				if (DEBUG) {
					debug("SKIP transform: " + sourcePath);
				}
			}
		} catch (Exception e) {
			e.printStackTrace(System.err);
			broken = true;
		}
	}

	public static PluginInfo getInfo(IPath sourcePath) {
		// The path is expected to have this form
		// Example:
		// /test-pro/.link_to_grails_plugins/audit-logging-0.5.4/grails-app/controllers/org/codehaus/groovy/grails/plugins/orm/auditable/AuditLogEventController.groovy
		// Pattern:
		// /<project-name>/.link_to_grails_plugins/<plugin-name>-<plugin-version>/<the-rest-of-it>

		// Also stuff in the 'test' folder is excluded from the transform
		// See grails.util.PluginBuildSettings.getPluginInfoForSource(String)
		// Test folder path looks like:
		// /<project-name>/.link_to_grails_plugins/<plugin-name>-<plugin-version>/test/<the-rest-of-it>

		if (sourcePath.segmentCount() > 3) {
			String link = sourcePath.segment(1);
			if (link.equals(SourceFile.LINK_TO_GRAILS_PLUGINS)) {
				String pluginNameAndVersion = sourcePath.segment(2);
				int split = pluginNameAndVersion.lastIndexOf('-');
				if (split >= 0) {
					if ("test".equals(sourcePath.segment(3))) {
						// Exclude "test" folder in plugins
						return null;
					} else {
						// Pattern matched, extract relevant info.
						return new PluginInfo(pluginNameAndVersion.substring(0, split), pluginNameAndVersion.substring(split + 1));
					}
				}
			}
		}
		// If the expected pattern isn't found, no info is extracted
		// => the transform will not apply.
		return null;
	}

}