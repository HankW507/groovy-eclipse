/*******************************************************************************
 * Copyright (c) 2009 Codehaus.org, SpringSource, and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *     Andy Clement        - Initial API and implementation
 *     Andrew Eisenberg - Additional work
 *******************************************************************************/
package org.codehaus.jdt.groovy.integration.internal;

import org.codehaus.groovy.control.CompilerConfiguration;
import org.codehaus.groovy.control.ErrorCollector;
import org.codehaus.groovy.control.Phases;
import org.codehaus.groovy.control.SourceUnit;
import org.codehaus.jdt.groovy.integration.LanguageSupport;
import org.codehaus.jdt.groovy.internal.compiler.ast.GroovyCompilationUnitDeclaration;
import org.codehaus.jdt.groovy.internal.compiler.ast.GroovyErrorCollectorForJDT;
import org.codehaus.jdt.groovy.internal.compiler.ast.GroovyTypeDeclaration;
import org.codehaus.jdt.groovy.internal.compiler.ast.JDTResolver;
import org.codehaus.jdt.groovy.model.GroovyCompilationUnit;
import org.codehaus.jdt.groovy.model.GroovyNature;
import org.eclipse.core.resources.IProject;
import org.eclipse.jdt.core.WorkingCopyOwner;
import org.eclipse.jdt.groovy.core.util.ContentTypeUtils;
import org.eclipse.jdt.groovy.core.util.GroovyUtils;
import org.eclipse.jdt.internal.compiler.CompilationResult;
import org.eclipse.jdt.internal.compiler.ast.CompilationUnitDeclaration;
import org.eclipse.jdt.internal.compiler.ast.TypeDeclaration;
import org.eclipse.jdt.internal.compiler.env.ICompilationUnit;
import org.eclipse.jdt.internal.compiler.lookup.LookupEnvironment;
import org.eclipse.jdt.internal.compiler.parser.Parser;
import org.eclipse.jdt.internal.compiler.problem.ProblemReporter;
import org.eclipse.jdt.internal.core.CompilationUnit;
import org.eclipse.jdt.internal.core.PackageFragment;
import org.eclipse.jdt.internal.core.util.Util;


/**
 * The groovy implementation of LanguageSupport. This class is dynamically loaded by jdt.core (so referenced by name from jdt.core)
 * - and then invoked to get a parser that can handle either groovy or java.
 * 
 * @author Andy Clement
 * 
 */
@SuppressWarnings("restriction")
public class GroovyLanguageSupport implements LanguageSupport {

	public Parser getParser(LookupEnvironment lookupEnvironment, ProblemReporter problemReporter,
			boolean parseLiteralExpressionsAsConstants, int variant) {
		if (variant == 1) {
			return new MultiplexingParser(lookupEnvironment, problemReporter, parseLiteralExpressionsAsConstants);
		} else { // variant==2
			return new MultiplexingCommentRecorderParser(lookupEnvironment, problemReporter, parseLiteralExpressionsAsConstants);
		}
	}

	public CompilationUnit newCompilationUnit(PackageFragment parent, String name, WorkingCopyOwner owner) {

		// should use a content type here
		if (ContentTypeUtils.isGroovyLikeFileName(name)) {
			return new GroovyCompilationUnit(parent, name, owner);
		} else {
			return new CompilationUnit(parent, name, owner);
		}
	}

	public CompilationUnitDeclaration newCompilationUnitDeclaration(ICompilationUnit unit, ProblemReporter problemReporter,
			CompilationResult compilationResult, int sourceLength) {
		if (ContentTypeUtils.isGroovyLikeFileName(compilationResult.getFileName())) {
			CompilerConfiguration groovyCompilerConfig = new CompilerConfiguration();
			// groovyCompilerConfig.setPluginFactory(new ErrorRecoveredCSTParserPluginFactory(null));
			ErrorCollector errorCollector = new GroovyErrorCollectorForJDT(groovyCompilerConfig);
			SourceUnit groovySourceUnit = new SourceUnit(new String(compilationResult.getFileName()),
					new String(unit.getContents()), groovyCompilerConfig, null, errorCollector);

			// FIXASC (M2) missing the classloader configuration (eg. to include transformers)
			org.codehaus.groovy.control.CompilationUnit groovyCU = new org.codehaus.groovy.control.CompilationUnit(
					groovyCompilerConfig);

			JDTResolver resolver = new JDTResolver(groovyCU);
			groovyCU.setResolveVisitor(resolver);

			// TODO groovy get this from the Antlr parser
			compilationResult.lineSeparatorPositions = GroovyUtils.getSourceLineSeparatorsIn(unit.getContents());

			groovyCU.addSource(groovySourceUnit);
			GroovyCompilationUnitDeclaration gcuDeclaration = new GroovyCompilationUnitDeclaration(problemReporter,
					compilationResult, sourceLength, groovyCU, groovySourceUnit);

			// boolean success =
			gcuDeclaration.processToPhase(Phases.CONVERSION);

			// Regardless of a successful outcome, build what is possible in the face of any errors
			gcuDeclaration.populateCompilationUnitDeclaration();
			for (TypeDeclaration decl : gcuDeclaration.types) {
				GroovyTypeDeclaration gtDeclaration = (GroovyTypeDeclaration) decl;
				resolver.record(gtDeclaration);
			}

			return gcuDeclaration;
		} else {
			return new CompilationUnitDeclaration(problemReporter, compilationResult, sourceLength);
		}
	}

	public boolean isInterestingProject(IProject project) {
		return GroovyNature.hasGroovyNature(project);
	}

	public boolean isSourceFile(String fileName, boolean isInterestingProject) {
		if (isInterestingProject) {
			return Util.isJavaLikeFileName(fileName);
		} else {
			return ContentTypeUtils.isJavaLikeButNotGroovyLikeExtension(fileName);
		}
	}

}
