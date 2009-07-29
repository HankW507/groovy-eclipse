/*******************************************************************************
 * Copyright (c) 2000, 2007 IBM Corporation and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *     IBM Corporation - initial API and implementation
 *******************************************************************************/
package org.eclipse.jdt.internal.compiler.ast;

import org.eclipse.jdt.internal.compiler.ASTVisitor;
import org.eclipse.jdt.internal.compiler.classfmt.ClassFileConstants;
import org.eclipse.jdt.internal.compiler.codegen.CodeStream;
import org.eclipse.jdt.internal.compiler.codegen.BranchLabel;
import org.eclipse.jdt.internal.compiler.flow.FlowContext;
import org.eclipse.jdt.internal.compiler.flow.FlowInfo;
import org.eclipse.jdt.internal.compiler.flow.LoopingFlowContext;
import org.eclipse.jdt.internal.compiler.flow.UnconditionalFlowInfo;
import org.eclipse.jdt.internal.compiler.impl.Constant;
import org.eclipse.jdt.internal.compiler.lookup.ArrayBinding;
import org.eclipse.jdt.internal.compiler.lookup.Binding;
import org.eclipse.jdt.internal.compiler.lookup.BlockScope;
import org.eclipse.jdt.internal.compiler.lookup.LocalVariableBinding;
import org.eclipse.jdt.internal.compiler.lookup.MethodBinding;
import org.eclipse.jdt.internal.compiler.lookup.ParameterizedTypeBinding;
import org.eclipse.jdt.internal.compiler.lookup.ReferenceBinding;
import org.eclipse.jdt.internal.compiler.lookup.TypeBinding;

public class ForeachStatement extends Statement {
    
	public LocalDeclaration elementVariable;
	public int elementVariableImplicitWidening = -1; 
	public Expression collection;
	public Statement action;
	
	// set the kind of foreach
	private int kind;
	// possible kinds of iterating behavior
	private static final int ARRAY = 0;
	private static final int RAW_ITERABLE = 1;
	private static final int GENERIC_ITERABLE = 2;

	private TypeBinding iteratorReceiverType;
	private TypeBinding collectionElementType;

	// loop labels
	private BranchLabel breakLabel;
	private BranchLabel continueLabel;
	
	public BlockScope scope;

	// secret variables for codegen
	public LocalVariableBinding indexVariable;
	public LocalVariableBinding collectionVariable;	// to store the collection expression value
	public LocalVariableBinding maxVariable;
	// secret variable names
	private static final char[] SecretIndexVariableName = " index".toCharArray(); //$NON-NLS-1$
	private static final char[] SecretCollectionVariableName = " collection".toCharArray(); //$NON-NLS-1$
	private static final char[] SecretMaxVariableName = " max".toCharArray(); //$NON-NLS-1$
	
	int postCollectionInitStateIndex = -1;
	int mergedInitStateIndex = -1;
	
	public ForeachStatement(
		LocalDeclaration elementVariable,
		int start) {

		this.elementVariable = elementVariable;
		this.sourceStart = start;
		this.kind = -1;
	}

	public FlowInfo analyseCode(
		BlockScope currentScope,
		FlowContext flowContext,
		FlowInfo flowInfo) {
		// initialize break and continue labels
		breakLabel = new BranchLabel();
		continueLabel = new BranchLabel();

		// process the element variable and collection
		this.collection.checkNPE(currentScope, flowContext, flowInfo);
		flowInfo = this.elementVariable.analyseCode(scope, flowContext, flowInfo);
		FlowInfo condInfo = this.collection.analyseCode(scope, flowContext, flowInfo.copy());

		// element variable will be assigned when iterating
		condInfo.markAsDefinitelyAssigned(this.elementVariable.binding);

		this.postCollectionInitStateIndex = currentScope.methodScope().recordInitializationStates(condInfo);
		
		// process the action
		LoopingFlowContext loopingContext = 
			new LoopingFlowContext(flowContext, flowInfo, this, breakLabel, 
				continueLabel, scope);
		UnconditionalFlowInfo actionInfo = 
			condInfo.nullInfoLessUnconditionalCopy();
		actionInfo.markAsDefinitelyUnknown(this.elementVariable.binding);
		FlowInfo exitBranch;
		if (!(action == null || (action.isEmptyBlock() 
		        	&& currentScope.compilerOptions().complianceLevel <= ClassFileConstants.JDK1_3))) {

			if (!this.action.complainIfUnreachable(actionInfo, scope, false)) {
				actionInfo = action.
					analyseCode(scope, loopingContext, actionInfo).
					unconditionalCopy();
			}

			// code generation can be optimized when no need to continue in the loop
			exitBranch = flowInfo.unconditionalCopy().
				addInitializationsFrom(condInfo.initsWhenFalse()); 
			// TODO (maxime) no need to test when false: can optimize (same for action being unreachable above) 
			if ((actionInfo.tagBits & loopingContext.initsOnContinue.tagBits &
					FlowInfo.UNREACHABLE) != 0) {
				continueLabel = null;
			} else {
				actionInfo = actionInfo.mergedWith(loopingContext.initsOnContinue);
				loopingContext.complainOnDeferredFinalChecks(scope, actionInfo);
				exitBranch.addPotentialInitializationsFrom(actionInfo);
			}
		} else {
			exitBranch = condInfo.initsWhenFalse();
		}

		// we need the variable to iterate the collection even if the 
		// element variable is not used
		final boolean hasEmptyAction = this.action == null
				|| this.action.isEmptyBlock()
				|| ((this.action.bits & IsUsefulEmptyStatement) != 0);

		switch(this.kind) {
			case ARRAY :
				if (!hasEmptyAction
						|| this.elementVariable.binding.resolvedPosition != -1) {
					this.collectionVariable.useFlag = LocalVariableBinding.USED;
					if (this.continueLabel != null) {
						this.indexVariable.useFlag = LocalVariableBinding.USED;
						this.maxVariable.useFlag = LocalVariableBinding.USED;
					}
				}
				break;
			case RAW_ITERABLE :
			case GENERIC_ITERABLE :
				this.indexVariable.useFlag = LocalVariableBinding.USED;
				break;
		}
		//end of loop
		loopingContext.complainOnDeferredNullChecks(currentScope, actionInfo);

		FlowInfo mergedInfo = FlowInfo.mergedOptimizedBranches(
				(loopingContext.initsOnBreak.tagBits &
					FlowInfo.UNREACHABLE) != 0 ?
					loopingContext.initsOnBreak :
					flowInfo.addInitializationsFrom(loopingContext.initsOnBreak), // recover upstream null info
				false, 
				exitBranch, 
				false, 
				true /*for(;;){}while(true); unreachable(); */);
		mergedInitStateIndex = currentScope.methodScope().recordInitializationStates(mergedInfo);
		return mergedInfo;
	}

	/**
	 * For statement code generation
	 *
	 * @param currentScope org.eclipse.jdt.internal.compiler.lookup.BlockScope
	 * @param codeStream org.eclipse.jdt.internal.compiler.codegen.CodeStream
	 */
	public void generateCode(BlockScope currentScope, CodeStream codeStream) {
	    
		if ((bits & IsReachable) == 0) {
			return;
		}
		int pc = codeStream.position;
		final boolean hasEmptyAction = this.action == null
			|| this.action.isEmptyBlock()
			|| ((this.action.bits & IsUsefulEmptyStatement) != 0);

		if (hasEmptyAction
				&& this.elementVariable.binding.resolvedPosition == -1
				&& this.kind == ARRAY) {
			collection.generateCode(scope, codeStream, false);
			codeStream.exitUserScope(scope);
			if (mergedInitStateIndex != -1) {
				codeStream.removeNotDefinitelyAssignedVariables(currentScope, mergedInitStateIndex);
				codeStream.addDefinitelyAssignedVariables(currentScope, mergedInitStateIndex);
			}
			codeStream.recordPositionsFrom(pc, this.sourceStart);
			return;
		}

		// generate the initializations
		switch(this.kind) {
			case ARRAY :
				collection.generateCode(scope, codeStream, true);
				codeStream.store(this.collectionVariable, true);
				codeStream.addVariable(this.collectionVariable);
				if (this.continueLabel != null) {
					// int length = (collectionVariable = [collection]).length;
					codeStream.arraylength();
					codeStream.store(this.maxVariable, false);
					codeStream.addVariable(this.maxVariable);
					codeStream.iconst_0();
					codeStream.store(this.indexVariable, false);
					codeStream.addVariable(this.indexVariable);
				} else {
					// leave collectionVariable on execution stack (will be consumed when swapping condition further down)
				}
				break;
			case RAW_ITERABLE :
			case GENERIC_ITERABLE :
				collection.generateCode(scope, codeStream, true);
				// declaringClass.iterator();
				MethodBinding iteratorMethodBinding =
					new MethodBinding(
							ClassFileConstants.AccPublic,
							"iterator".toCharArray(),//$NON-NLS-1$
							scope.getJavaUtilIterator(),
							Binding.NO_PARAMETERS,
							Binding.NO_EXCEPTIONS,
							(ReferenceBinding) this.iteratorReceiverType.erasure());
				if (this.iteratorReceiverType.isInterface()) {
					codeStream.invokeinterface(iteratorMethodBinding);
				} else {
					codeStream.invokevirtual(iteratorMethodBinding);
				}
				codeStream.store(this.indexVariable, false);
				codeStream.addVariable(this.indexVariable);
				break;
		}
		// label management
		BranchLabel actionLabel = new BranchLabel(codeStream);
		actionLabel.tagBits |= BranchLabel.USED;
		BranchLabel conditionLabel = new BranchLabel(codeStream);
		conditionLabel.tagBits |= BranchLabel.USED;
		breakLabel.initialize(codeStream);
		if (this.continueLabel == null) {
			// generate the condition (swapped for optimizing)
			conditionLabel.place();
			int conditionPC = codeStream.position;
			switch(this.kind) {
				case ARRAY :
					// inline the arraylength call
					// collectionVariable is already on execution stack
					codeStream.arraylength();
					codeStream.ifeq(breakLabel);
					break;
				case RAW_ITERABLE :
				case GENERIC_ITERABLE :
					codeStream.load(this.indexVariable);
					codeStream.invokeJavaUtilIteratorHasNext();
					codeStream.ifeq(breakLabel);
					break;
			}
			codeStream.recordPositionsFrom(conditionPC, this.elementVariable.sourceStart);
		} else {
			this.continueLabel.initialize(codeStream);
			this.continueLabel.tagBits |= BranchLabel.USED;
			// jump over the actionBlock
			codeStream.goto_(conditionLabel);
		}

		// generate the loop action
		actionLabel.place();

		// generate the loop action
		switch(this.kind) {
			case ARRAY :
				if (this.elementVariable.binding.resolvedPosition != -1) {
					codeStream.load(this.collectionVariable);
					if (this.continueLabel == null) {
						codeStream.iconst_0(); // no continue, thus simply hardcode offset 0
					} else {
						codeStream.load(this.indexVariable);
					}
					codeStream.arrayAt(this.collectionElementType.id);
					if (this.elementVariableImplicitWidening != -1) {
						codeStream.generateImplicitConversion(this.elementVariableImplicitWidening);
					}
					codeStream.store(this.elementVariable.binding, false);
					codeStream.addVisibleLocalVariable(this.elementVariable.binding);
					if (this.postCollectionInitStateIndex != -1) {
						codeStream.addDefinitelyAssignedVariables(
							currentScope,
							this.postCollectionInitStateIndex);
					}
				}
				break;
			case RAW_ITERABLE :
			case GENERIC_ITERABLE :
				codeStream.load(this.indexVariable);
				codeStream.invokeJavaUtilIteratorNext();
				if (this.elementVariable.binding.type.id != T_JavaLangObject) {
					if (this.elementVariableImplicitWidening != -1) {
						codeStream.checkcast(this.collectionElementType);
						codeStream.generateImplicitConversion(this.elementVariableImplicitWidening);
					} else {
						codeStream.checkcast(this.elementVariable.binding.type);
					}
				}
				if (this.elementVariable.binding.resolvedPosition == -1) {
					codeStream.pop();
				} else {
					codeStream.store(this.elementVariable.binding, false);
					codeStream.addVisibleLocalVariable(this.elementVariable.binding);
					if (this.postCollectionInitStateIndex != -1) {
						codeStream.addDefinitelyAssignedVariables(
							currentScope,
							this.postCollectionInitStateIndex);
					}
				}
				break;
		}

		if (!hasEmptyAction) {
			this.action.generateCode(scope, codeStream);
		}
		codeStream.removeVariable(this.elementVariable.binding);
		if (this.postCollectionInitStateIndex != -1) {
			codeStream.removeNotDefinitelyAssignedVariables(currentScope, this.postCollectionInitStateIndex);
		}
		// continuation point
		if (this.continueLabel != null) {
			this.continueLabel.place();
			int continuationPC = codeStream.position;
			// generate the increments for next iteration
			switch(this.kind) {
				case ARRAY :
					if (!hasEmptyAction || this.elementVariable.binding.resolvedPosition >= 0) {
						codeStream.iinc(this.indexVariable.resolvedPosition, 1);
					}
					// generate the condition
					conditionLabel.place();
					codeStream.load(this.indexVariable);
					codeStream.load(this.maxVariable);
					codeStream.if_icmplt(actionLabel);
					break;
				case RAW_ITERABLE :
				case GENERIC_ITERABLE :
					// generate the condition
					conditionLabel.place();
					codeStream.load(this.indexVariable);
					codeStream.invokeJavaUtilIteratorHasNext();
					codeStream.ifne(actionLabel);
					break;
			}
			codeStream.recordPositionsFrom(continuationPC, this.elementVariable.sourceStart);
		}
		switch(this.kind) {
			case ARRAY :
				codeStream.removeVariable(this.indexVariable);
				codeStream.removeVariable(this.maxVariable);
				codeStream.removeVariable(this.collectionVariable);
				break;
			case RAW_ITERABLE :
			case GENERIC_ITERABLE :
				// generate the condition
				codeStream.removeVariable(this.indexVariable);
				break;
		}
		codeStream.exitUserScope(scope);
		if (mergedInitStateIndex != -1) {
			codeStream.removeNotDefinitelyAssignedVariables(currentScope, mergedInitStateIndex);
			codeStream.addDefinitelyAssignedVariables(currentScope, mergedInitStateIndex);
		}
		breakLabel.place();
		codeStream.recordPositionsFrom(pc, this.sourceStart);
	}

	public StringBuffer printStatement(int indent, StringBuffer output) {

		printIndent(indent, output).append("for ("); //$NON-NLS-1$
		this.elementVariable.printAsExpression(0, output); 
		output.append(" : ");//$NON-NLS-1$
		this.collection.print(0, output).append(") "); //$NON-NLS-1$
		//block
		if (this.action == null) {
			output.append(';');
		} else {
			output.append('\n');
			this.action.printStatement(indent + 1, output);
		}
		return output;
	}

	public void resolve(BlockScope upperScope) {
		// use the scope that will hold the init declarations
		scope = new BlockScope(upperScope);
		this.elementVariable.resolve(scope); // collection expression can see itemVariable
		TypeBinding elementType = this.elementVariable.type.resolvedType;
		TypeBinding collectionType = this.collection == null ? null : this.collection.resolveType(scope);

		if (elementType != null && collectionType != null) {
			if (collectionType.isArrayType()) { // for(E e : E[])
				this.kind = ARRAY;
				this.collection.computeConversion(scope,collectionType, collectionType);
				this.collectionElementType = ((ArrayBinding) collectionType).elementsType();
				if (!collectionElementType.isCompatibleWith(elementType)
						&& !scope.isBoxingCompatibleWith(collectionElementType, elementType)) {
					scope.problemReporter().notCompatibleTypesErrorInForeach(collection, collectionElementType, elementType);
				}
				// in case we need to do a conversion
				int compileTimeTypeID = collectionElementType.id;
				if (elementType.isBaseType()) {
					if (!collectionElementType.isBaseType()) {
						compileTimeTypeID = scope.environment().computeBoxingType(collectionElementType).id;
						this.elementVariableImplicitWidening = UNBOXING;
						if (elementType.isBaseType()) {
							this.elementVariableImplicitWidening |= (elementType.id << 4) + compileTimeTypeID;
							scope.problemReporter().autoboxing(collection, collectionElementType, elementType);
						}
					} else {
						this.elementVariableImplicitWidening = (elementType.id << 4) + compileTimeTypeID;
					}
				} else {
					if (collectionElementType.isBaseType()) {
						int boxedID = scope.environment().computeBoxingType(collectionElementType).id;
						this.elementVariableImplicitWidening = BOXING | (compileTimeTypeID << 4) | compileTimeTypeID; // use primitive type in implicit conversion
						compileTimeTypeID = boxedID;
						scope.problemReporter().autoboxing(collection, collectionElementType, elementType);
					}
				}
			} else if (collectionType instanceof ReferenceBinding) {
			    ReferenceBinding iterableType = ((ReferenceBinding)collectionType).findSuperTypeOriginatingFrom(T_JavaLangIterable, false /*Iterable is not a class*/);
			    checkIterable: {
			    	if (iterableType == null) break checkIterable;
			    	
					this.iteratorReceiverType = collectionType.erasure();
					if (((ReferenceBinding)iteratorReceiverType).findSuperTypeOriginatingFrom(T_JavaLangIterable, false) == null) {
						this.iteratorReceiverType = iterableType; // handle indirect inheritance thru variable secondary bound
	   					this.collection.computeConversion(scope, iterableType, collectionType);
					} else {
	   					this.collection.computeConversion(scope, collectionType, collectionType);
					}

			    	TypeBinding[] arguments = null;
			    	switch (iterableType.kind()) {
			    		case Binding.RAW_TYPE : // for(Object o : Iterable)
							this.kind = RAW_ITERABLE;
							this.collectionElementType = scope.getJavaLangObject();
							if (!collectionElementType.isCompatibleWith(elementType)
									&& !scope.isBoxingCompatibleWith(collectionElementType, elementType)) {
								scope.problemReporter().notCompatibleTypesErrorInForeach(collection, collectionElementType, elementType);
							}
							// no conversion needed as only for reference types
			    			break checkIterable;
			    			
			    		case Binding.GENERIC_TYPE : // for (T t : Iterable<T>) - in case used inside Iterable itself
			    			arguments = iterableType.typeVariables();
			    			break;
			    			
			    		case Binding.PARAMETERIZED_TYPE : // for(E e : Iterable<E>)
			    			arguments = ((ParameterizedTypeBinding)iterableType).arguments;
			    			break;
			    			
			    		default:
			    			break checkIterable;
			    	}
			    	// generic or parameterized case
					if (arguments.length != 1) break checkIterable; // per construction can only be one
					this.kind = GENERIC_ITERABLE;
					
					this.collectionElementType = arguments[0]; 
					if (!collectionElementType.isCompatibleWith(elementType)
							&& !scope.isBoxingCompatibleWith(collectionElementType, elementType)) {
						scope.problemReporter().notCompatibleTypesErrorInForeach(collection, collectionElementType, elementType);
					}
					int compileTimeTypeID = collectionElementType.id;
					// no conversion needed as only for reference types
					if (elementType.isBaseType()) {
						if (!collectionElementType.isBaseType()) {
							compileTimeTypeID = scope.environment().computeBoxingType(collectionElementType).id;
							this.elementVariableImplicitWidening = UNBOXING;
							if (elementType.isBaseType()) {
								this.elementVariableImplicitWidening |= (elementType.id << 4) + compileTimeTypeID;
							}
						} else {
							this.elementVariableImplicitWidening = (elementType.id << 4) + compileTimeTypeID;
						}
					} else {
						if (collectionElementType.isBaseType()) {
							int boxedID = scope.environment().computeBoxingType(collectionElementType).id;
							this.elementVariableImplicitWidening = BOXING | (compileTimeTypeID << 4) | compileTimeTypeID; // use primitive type in implicit conversion
							compileTimeTypeID = boxedID;
						}
					}
			    }
			}
			switch(this.kind) {
				case ARRAY :
					// allocate #index secret variable (of type int)
					this.indexVariable = new LocalVariableBinding(SecretIndexVariableName, TypeBinding.INT, ClassFileConstants.AccDefault, false);
					scope.addLocalVariable(this.indexVariable);
					this.indexVariable.setConstant(Constant.NotAConstant); // not inlinable
					
					// allocate #max secret variable
					this.maxVariable = new LocalVariableBinding(SecretMaxVariableName, TypeBinding.INT, ClassFileConstants.AccDefault, false);
					scope.addLocalVariable(this.maxVariable);
					this.maxVariable.setConstant(Constant.NotAConstant); // not inlinable
					// add #array secret variable (of collection type)
					this.collectionVariable = new LocalVariableBinding(SecretCollectionVariableName, collectionType, ClassFileConstants.AccDefault, false);
					scope.addLocalVariable(this.collectionVariable);
					this.collectionVariable.setConstant(Constant.NotAConstant); // not inlinable
					break;
				case RAW_ITERABLE :
				case GENERIC_ITERABLE :
					// allocate #index secret variable (of type Iterator)
					this.indexVariable = new LocalVariableBinding(SecretIndexVariableName, scope.getJavaUtilIterator(), ClassFileConstants.AccDefault, false);
					scope.addLocalVariable(this.indexVariable);
					this.indexVariable.setConstant(Constant.NotAConstant); // not inlinable
					break;
				default :
					scope.problemReporter().invalidTypeForCollection(collection);
			}
		}
		if (action != null) {
			action.resolve(scope);
		}
	}
	
	public void traverse(
		ASTVisitor visitor,
		BlockScope blockScope) {

		if (visitor.visit(this, blockScope)) {
			this.elementVariable.traverse(visitor, scope);
			this.collection.traverse(visitor, scope);
			if (action != null) {
				action.traverse(visitor, scope);
			}
		}
		visitor.endVisit(this, blockScope);
	}
}
