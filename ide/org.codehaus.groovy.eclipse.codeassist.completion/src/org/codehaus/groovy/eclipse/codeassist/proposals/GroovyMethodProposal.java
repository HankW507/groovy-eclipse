 /*
 * Copyright 2003-2009 the original author or authors.
 *
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

package org.codehaus.groovy.eclipse.codeassist.proposals;

import org.codehaus.groovy.ast.AnnotatedNode;
import org.codehaus.groovy.ast.ConstructorNode;
import org.codehaus.groovy.ast.MethodNode;
import org.codehaus.groovy.ast.Parameter;
import org.codehaus.groovy.eclipse.GroovyPlugin;
import org.codehaus.groovy.eclipse.codeassist.ProposalUtils;
import org.codehaus.groovy.eclipse.codeassist.processors.GroovyCompletionProposal;
import org.codehaus.groovy.eclipse.codeassist.proposals.GroovyJavaMethodCompletionProposal.ProposalOptions;
import org.codehaus.groovy.eclipse.codeassist.requestor.ContentAssistContext;
import org.codehaus.groovy.eclipse.core.GroovyCore;
import org.codehaus.groovy.eclipse.core.preferences.PreferenceConstants;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.jdt.core.CompletionFlags;
import org.eclipse.jdt.core.CompletionProposal;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IMethod;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.core.JavaModelException;
import org.eclipse.jdt.ui.text.java.IJavaCompletionProposal;
import org.eclipse.jdt.ui.text.java.JavaContentAssistInvocationContext;
import org.eclipse.jface.preference.IPreferenceStore;

/**
 * @author Andrew Eisenberg
 * @created Nov 12, 2009
 *
 */
public class GroovyMethodProposal extends AbstractGroovyProposal {

    protected final MethodNode method;
    private String contributor;

    private boolean useNamedArguments; // allow individual method proposal contributors to override
                                       // the setting in the preferences

    public GroovyMethodProposal(MethodNode method) {
        super();
        this.method = method;
        contributor = "Groovy";
        useNamedArguments = false;
    }
    public GroovyMethodProposal(MethodNode method, String contributor) {
        this(method);
        this.contributor = contributor;
    }

    public void setUseNamedArguments(boolean useNamedArguments) {
        this.useNamedArguments = useNamedArguments;
    }

    @Override
    protected AnnotatedNode getAssociatedNode() {
        return method;
    }

    public IJavaCompletionProposal createJavaProposal(
            ContentAssistContext context,
            JavaContentAssistInvocationContext javaContext) {
        GroovyCompletionProposal proposal = new GroovyCompletionProposal(
                CompletionProposal.METHOD_REF, context.completionLocation);

        proposal.setCompletion(completionName());
        proposal.setDeclarationSignature(ProposalUtils.createTypeSignature(method.getDeclaringClass()));
        proposal.setName(method.getName().toCharArray());
        proposal.setParameterNames(createParameterNames(context.unit));
        proposal.setParameterTypeNames(createParameterTypeNames(method));
        proposal.setReplaceRange(context.completionLocation - context.completionExpression.length(), context.completionLocation - context.completionExpression.length());
        proposal.setFlags(getModifiers());
        proposal.setAdditionalFlags(CompletionFlags.Default);
        char[] methodSignature = createMethodSignature();
        proposal.setKey(methodSignature);
        proposal.setSignature(methodSignature);
        proposal.setRelevance(computeRelevance());

        // FIXADE refactor this so it is not calculated for each proposal
        IPreferenceStore prefs = GroovyPlugin.getDefault().getPreferenceStore();
        ProposalOptions groovyFormatterPrefs = new ProposalOptions(
                prefs.getBoolean(PreferenceConstants.GROOVY_CONTENT_ASSIST_NOPARENS),
                prefs.getBoolean(PreferenceConstants.GROOVY_CONTENT_ASSIST_BRACKETS),
                shouldUseNamedArguments(prefs));

        // would be nice to use a ParameterGuessingProposal, but that requires
        // setting the extended data of the coreContext. We don't really have
        // access to all that information
        // LazyJavaCompletionProposal lazyProposal = null;
        // lazyProposal = ParameterGuessingProposal.createProposal(proposal,
        // javaContext, true);
        // if (lazyProposal == null) {
        // lazyProposal = new FilledArgumentNamesMethodProposal(proposal,
        // javaContext);
        // }
        // return lazyProposal;

        return new GroovyJavaMethodCompletionProposal(proposal, javaContext,
                groovyFormatterPrefs, contributor);

    }
    protected boolean shouldUseNamedArguments(IPreferenceStore prefs) {
        return (prefs
                .getBoolean(PreferenceConstants.GROOVY_CONTENT_NAMED_ARGUMENTS) && method instanceof ConstructorNode)
                || useNamedArguments;
    }

    protected char[] createMethodSignature() {
        return ProposalUtils.createMethodSignature(method);
    }

    protected int getModifiers() {
        return method.getModifiers();
    }


    protected char[] completionName() {
        String name = method.getName();
        char[] nameArr = name.toCharArray();
        boolean hasWhitespace = false;
        for (int i = 0; i < nameArr.length; i++) {
            if (Character.isWhitespace(nameArr[i])) {
                hasWhitespace = true;
                break;
            }
        }
        if (hasWhitespace) {
            name = "\"" + name + "\"";
        }
        return (name + "()").toCharArray();
    }

    protected char[][] createParameterNames(ICompilationUnit unit) {

        Parameter[] params = method.getParameters();
        int numParams = params == null ? 0 : params.length;

        // short circuit
        if (numParams == 0) {
            return new char[0][];
        }

        char[][] paramNames = null;
        // if the MethodNode has param names filled in, then use that
        if (params[0].getName().equals("arg0")
                || params[0].getName().equals("param0")) {
            paramNames = getParameterNames(unit, method);
        }

        if (paramNames == null) {
            paramNames = new char[params.length][];
            for (int i = 0; i < params.length; i++) {
                paramNames[i] = params[i].getName().toCharArray();
            }
        }

        return paramNames;
    }

    protected char[][] createParameterTypeNames(MethodNode method) {
        char[][] typeNames = new char[method.getParameters().length][];
        int i = 0;
        for (Parameter param : method.getParameters()) {
            typeNames[i] = ProposalUtils.createSimpleTypeName(param.getType());
            i++;
        }
        return typeNames;
    }

    /**
     * FIXADE I am concerned that this takes a long time since we are doing a lookup for each method
     * any way to cache?
     * @throws JavaModelException
     */
    protected char[][] getParameterNames(ICompilationUnit unit, MethodNode method) {
        try {
            IType type = unit.getJavaProject().findType(method.getDeclaringClass().getName(), new NullProgressMonitor());
            if (type != null && type.exists()) {
                Parameter[] params = method.getParameters();
                int numParams = params == null ? 0 : params.length;

                if (numParams == 0) {
                    return new char[0][];
                }

                String[] parameterTypeSignatures = new String[numParams];
                boolean doResolved = type.isBinary();
                for (int i = 0; i < parameterTypeSignatures.length; i++) {
                    if (doResolved) {
                        parameterTypeSignatures[i] = ProposalUtils.createTypeSignatureStr(params[i].getType());
                    } else {
                        parameterTypeSignatures[i] = ProposalUtils.createUnresolvedTypeSignatureStr(params[i].getType());
                    }
                }
                IMethod jdtMethod = null;

                // try to find the precise method
                IMethod maybeMethod = type.getMethod(method.getName(),
                        parameterTypeSignatures);
                if (maybeMethod != null && maybeMethod.exists()) {
                    jdtMethod = maybeMethod;
                } else {
                    // try something else and be a little more lenient
                    // look for any methods with the same name and number of
                    // arguments
                    IMethod[] methods = type.getMethods();
                    for (IMethod maybeMethod2 : methods) {
                        if (maybeMethod2.getElementName().equals(
                                method.getName())
                                && maybeMethod2.getNumberOfParameters() == numParams) {
                            jdtMethod = maybeMethod2;
                        }
                    }
                }

                // method was found somehow...return it.
                if (jdtMethod != null) {
                    String[] paramNames = jdtMethod.getParameterNames();
                    char[][] paramNamesChar = new char[paramNames.length][];
                    for (int i = 0; i < paramNames.length; i++) {
                        paramNamesChar[i] = paramNames[i].toCharArray();
                    }
                    return paramNamesChar;
                }
            }
        } catch (JavaModelException e) {
            GroovyCore.logException("Exception while looking for parameter types of " + method.getName(), e);
        }
        return null;
    }

    /**
	 * @return the method
	 */
	public MethodNode getMethod() {
		return method;
	}
}
