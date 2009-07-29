/*******************************************************************************
 * Copyright (c) 2002, 2008 IBM Corporation and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *     IBM Corporation - initial API and implementation
 *******************************************************************************/
package org.eclipse.jdt.internal.core.util;

import java.util.Locale;
import java.util.Map;

import org.eclipse.jdt.core.compiler.CategorizedProblem;
import org.eclipse.jdt.internal.compiler.CompilationResult;
import org.eclipse.jdt.internal.compiler.DefaultErrorHandlingPolicies;
import org.eclipse.jdt.internal.compiler.ast.ASTNode;
import org.eclipse.jdt.internal.compiler.ast.CompilationUnitDeclaration;
import org.eclipse.jdt.internal.compiler.ast.ConstructorDeclaration;
import org.eclipse.jdt.internal.compiler.ast.Expression;
import org.eclipse.jdt.internal.compiler.batch.CompilationUnit;
import org.eclipse.jdt.internal.compiler.env.ICompilationUnit;
import org.eclipse.jdt.internal.compiler.impl.CompilerOptions;
import org.eclipse.jdt.internal.compiler.problem.DefaultProblemFactory;
import org.eclipse.jdt.internal.compiler.problem.ProblemReporter;

/**
 * Utility class to parse different code snippets
 */
public class CodeSnippetParsingUtil {

	public RecordedParsingInformation recordedParsingInformation;

	private RecordedParsingInformation getRecordedParsingInformation(CompilationResult compilationResult, CommentRecorderParser parser) {
		int problemsCount = compilationResult.problemCount;
		CategorizedProblem[] problems = null;
		if (problemsCount != 0) {
			final CategorizedProblem[] compilationResultProblems = compilationResult.problems;
			if (compilationResultProblems.length == problemsCount) {
				problems = compilationResultProblems;
			} else {
				System.arraycopy(compilationResultProblems, 0, (problems = new CategorizedProblem[problemsCount]), 0, problemsCount);
			}
		}
		return new RecordedParsingInformation(problems, compilationResult.getLineSeparatorPositions(), parser.getCommentsPositions());
	}

	public ASTNode[] parseClassBodyDeclarations(char[] source, Map settings, boolean recordParsingInformation) {
		return parseClassBodyDeclarations(source, 0, source.length, settings, recordParsingInformation);
	}	

	public ASTNode[] parseClassBodyDeclarations(char[] source, int offset, int length, Map settings, boolean recordParsingInformation) {
		if (source == null) {
			throw new IllegalArgumentException();
		}
		CompilerOptions compilerOptions = new CompilerOptions(settings);
		final ProblemReporter problemReporter = new ProblemReporter(
					DefaultErrorHandlingPolicies.proceedWithAllProblems(), 
					compilerOptions, 
					new DefaultProblemFactory(Locale.getDefault()));
					
		CommentRecorderParser parser = new CommentRecorderParser(problemReporter, false);
		parser.setMethodsFullRecovery(false);
		parser.setStatementsRecovery(false);
		
		ICompilationUnit sourceUnit = 
			new CompilationUnit(
				source, 
				"", //$NON-NLS-1$
				compilerOptions.defaultEncoding);

		CompilationResult compilationResult = new CompilationResult(sourceUnit, 0, 0, compilerOptions.maxProblemsPerUnit);
		final CompilationUnitDeclaration compilationUnitDeclaration = new CompilationUnitDeclaration(problemReporter, compilationResult, source.length);
		ASTNode[] result = parser.parseClassBodyDeclarations(source, offset, length, compilationUnitDeclaration);
		
		if (recordParsingInformation) {
			this.recordedParsingInformation = getRecordedParsingInformation(compilationResult, parser);
		}
		return result;
	}

	public CompilationUnitDeclaration parseCompilationUnit(char[] source, Map settings, boolean recordParsingInformation) {
		if (source == null) {
			throw new IllegalArgumentException();
		}
		CompilerOptions compilerOptions = new CompilerOptions(settings);
		CommentRecorderParser parser =
			new CommentRecorderParser(
				new ProblemReporter(
					DefaultErrorHandlingPolicies.proceedWithAllProblems(), 
					compilerOptions, 
					new DefaultProblemFactory(Locale.getDefault())),
			false);
		
		ICompilationUnit sourceUnit = 
			new CompilationUnit(
				source, 
				"", //$NON-NLS-1$
				compilerOptions.defaultEncoding);
		final CompilationResult compilationResult = new CompilationResult(sourceUnit, 0, 0, compilerOptions.maxProblemsPerUnit);
		CompilationUnitDeclaration compilationUnitDeclaration = parser.dietParse(sourceUnit, compilationResult);

		if (recordParsingInformation) {
			this.recordedParsingInformation = getRecordedParsingInformation(compilationResult, parser);
		}
		
		if (compilationUnitDeclaration.ignoreMethodBodies) {
			compilationUnitDeclaration.ignoreFurtherInvestigation = true;
			// if initial diet parse did not work, no need to dig into method bodies.
			return compilationUnitDeclaration; 
		}
		
		//fill the methods bodies in order for the code to be generated
		//real parse of the method....
		parser.scanner.setSource(compilationResult);
		org.eclipse.jdt.internal.compiler.ast.TypeDeclaration[] types = compilationUnitDeclaration.types;
		if (types != null) {
			for (int i = 0, length = types.length; i < length; i++) {
				types[i].parseMethods(parser, compilationUnitDeclaration);
			}
		}
		
		if (recordParsingInformation) {
			this.recordedParsingInformation.updateRecordedParsingInformation(compilationResult);
		}
		return compilationUnitDeclaration;
	}

	public Expression parseExpression(char[] source, Map settings, boolean recordParsingInformation) {
		return parseExpression(source, 0, source.length, settings, recordParsingInformation);
	}
	
	public Expression parseExpression(char[] source, int offset, int length, Map settings, boolean recordParsingInformation) {
		
		if (source == null) {
			throw new IllegalArgumentException();
		}
		CompilerOptions compilerOptions = new CompilerOptions(settings);
		final ProblemReporter problemReporter = new ProblemReporter(
					DefaultErrorHandlingPolicies.proceedWithAllProblems(), 
					compilerOptions, 
					new DefaultProblemFactory(Locale.getDefault()));
					
		CommentRecorderParser parser = new CommentRecorderParser(problemReporter, false);

		ICompilationUnit sourceUnit = 
			new CompilationUnit(
				source, 
				"", //$NON-NLS-1$
				compilerOptions.defaultEncoding);

		CompilationResult compilationResult = new CompilationResult(sourceUnit, 0, 0, compilerOptions.maxProblemsPerUnit);
		Expression result = parser.parseExpression(source, offset, length, new CompilationUnitDeclaration(problemReporter, compilationResult, source.length));
		
		if (recordParsingInformation) {
			this.recordedParsingInformation = getRecordedParsingInformation(compilationResult, parser);
		}
		return result;
	}

	public ConstructorDeclaration parseStatements(char[] source, Map settings, boolean recordParsingInformation, boolean enabledStatementRecovery) {
		return parseStatements(source, 0, source.length, settings, recordParsingInformation, enabledStatementRecovery);
	}
	
	public ConstructorDeclaration parseStatements(char[] source, int offset, int length, Map settings, boolean recordParsingInformation, boolean enabledStatementRecovery) {
		if (source == null) {
			throw new IllegalArgumentException();
		}
		CompilerOptions compilerOptions = new CompilerOptions(settings);
		final ProblemReporter problemReporter = new ProblemReporter(
					DefaultErrorHandlingPolicies.proceedWithAllProblems(), 
					compilerOptions, 
					new DefaultProblemFactory(Locale.getDefault()));
		CommentRecorderParser parser = new CommentRecorderParser(problemReporter, false);
		parser.setMethodsFullRecovery(false);
		parser.setStatementsRecovery(enabledStatementRecovery);
		
		ICompilationUnit sourceUnit = 
			new CompilationUnit(
				source, 
				"", //$NON-NLS-1$
				compilerOptions.defaultEncoding);

		final CompilationResult compilationResult = new CompilationResult(sourceUnit, 0, 0, compilerOptions.maxProblemsPerUnit);
		CompilationUnitDeclaration compilationUnitDeclaration = new CompilationUnitDeclaration(problemReporter, compilationResult, length);		

		ConstructorDeclaration constructorDeclaration = new ConstructorDeclaration(compilationResult);
		constructorDeclaration.sourceEnd  = -1;
		constructorDeclaration.declarationSourceEnd = offset + length - 1;
		constructorDeclaration.bodyStart = offset;
		constructorDeclaration.bodyEnd = offset + length - 1;
		
		parser.scanner.setSource(compilationResult);
		parser.scanner.resetTo(offset, offset + length);
		parser.parse(constructorDeclaration, compilationUnitDeclaration, true);
		
		if (recordParsingInformation) {
			this.recordedParsingInformation = getRecordedParsingInformation(compilationResult, parser);
		}
		return constructorDeclaration;
	}
}
