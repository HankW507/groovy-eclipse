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
package org.eclipse.jdt.internal.compiler.parser.diagnose;

import org.eclipse.jdt.core.compiler.CharOperation;
import org.eclipse.jdt.core.compiler.InvalidInputException;
import org.eclipse.jdt.internal.compiler.parser.Scanner;
import org.eclipse.jdt.internal.compiler.parser.TerminalTokens;
import org.eclipse.jdt.internal.compiler.util.Util;

public class LexStream implements TerminalTokens {
	public static final int IS_AFTER_JUMP = 1;
	public static final int LBRACE_MISSING = 2;
		
	public static class Token{
		int kind;
		char[] name;
		int start;
		int end;
		int line;
		int flags;
		
		public String toString() {
			StringBuffer buffer = new StringBuffer();
			buffer.append(name).append('[').append(kind).append(']');
			buffer.append('{').append(start).append(',').append(end).append('}').append(line);
			return buffer.toString();
		}

	}

	private int tokenCacheIndex;
	private int tokenCacheEOFIndex;
	private Token[] tokenCache;

	private int currentIndex = -1;

	private Scanner scanner;
	private int[] intervalStartToSkip;
	private int[] intervalEndToSkip;
	private int[] intervalFlagsToSkip;
	
	private int previousInterval = -1;
	private int currentInterval = -1;
	
	public LexStream(int size, Scanner scanner, int[] intervalStartToSkip, int[] intervalEndToSkip, int[] intervalFlagsToSkip, int firstToken, int init, int eof) {
		this.tokenCache = new Token[size];
		this.tokenCacheIndex = 0;
		this.tokenCacheEOFIndex = Integer.MAX_VALUE;
		this.tokenCache[0] = new Token();
		this.tokenCache[0].kind = firstToken;
		this.tokenCache[0].name = CharOperation.NO_CHAR;
		this.tokenCache[0].start = init;
		this.tokenCache[0].end = init;
		this.tokenCache[0].line = 0;
		
		this.intervalStartToSkip = intervalStartToSkip;
		this.intervalEndToSkip = intervalEndToSkip;
		this.intervalFlagsToSkip = intervalFlagsToSkip;
		
		scanner.resetTo(init, eof);
		this.scanner = scanner;
	}
	
	private void readTokenFromScanner(){
		int length = tokenCache.length;
		boolean tokenNotFound = true;
		
		while(tokenNotFound) {
			try {
				int tokenKind =  scanner.getNextToken();
				if(tokenKind != TokenNameEOF) {
					int start = scanner.getCurrentTokenStartPosition();
					int end = scanner.getCurrentTokenEndPosition();
					
					int nextInterval = currentInterval + 1;
					if(intervalStartToSkip.length == 0 ||
							nextInterval >= intervalStartToSkip.length ||
							start < intervalStartToSkip[nextInterval]) {
						Token token = new Token();
						token.kind = tokenKind;
						token.name = scanner.getCurrentTokenSource();
						token.start = start;
						token.end = end;
						token.line = Util.getLineNumber(end, scanner.lineEnds, 0, scanner.linePtr);
						
						if(currentInterval != previousInterval && (intervalFlagsToSkip[currentInterval] & RangeUtil.IGNORE) == 0){
							token.flags = IS_AFTER_JUMP;
							if((intervalFlagsToSkip[currentInterval] & RangeUtil.LBRACE_MISSING) != 0){
								token.flags |= LBRACE_MISSING;
							}
						}
						previousInterval = currentInterval;

						tokenCache[++tokenCacheIndex % length] = token;
						
						tokenNotFound = false;
					} else {
						scanner.resetTo(intervalEndToSkip[++currentInterval] + 1, scanner.eofPosition - 1);
					}
				} else {
					int start = scanner.getCurrentTokenStartPosition();
					int end = scanner.getCurrentTokenEndPosition();
					Token token = new Token();
					token.kind = tokenKind;
					token.name = CharOperation.NO_CHAR;
					token.start = start;
					token.end = end;
					token.line = Util.getLineNumber(end, scanner.lineEnds, 0, scanner.linePtr);
					
					tokenCache[++tokenCacheIndex % length] = token;
					
					tokenCacheEOFIndex = tokenCacheIndex;
					tokenNotFound = false;
				}
			} catch (InvalidInputException e) {
				// return next token
			}
		}
	}
	
	public Token token(int index) {
		if(index < 0) {
			Token eofToken = new Token();
			eofToken.kind = TokenNameEOF;
			eofToken.name = CharOperation.NO_CHAR;
			return eofToken;
		}
		if(this.tokenCacheEOFIndex >= 0 && index > this.tokenCacheEOFIndex) {
			return token(this.tokenCacheEOFIndex);
		}
		int length = tokenCache.length;
		if(index > this.tokenCacheIndex) {
			int tokensToRead = index - this.tokenCacheIndex;
			while(tokensToRead-- != 0) {
				readTokenFromScanner();
			}
		} else if(this.tokenCacheIndex - length >= index) {
			return null;
		}
		
		return tokenCache[index % length];
	}
	
	
	
	public int getToken() {
		return currentIndex = next(currentIndex);
	}
	
	public int previous(int tokenIndex) {
		return tokenIndex > 0 ? tokenIndex - 1 : 0;
	}

	public int next(int tokenIndex) {
		return tokenIndex < this.tokenCacheEOFIndex ? tokenIndex + 1 : this.tokenCacheEOFIndex;
	}

	public boolean afterEol(int i) {
		return i < 1 ? true : line(i - 1) < line(i);
	}
	
	public void reset() {
		currentIndex = -1;
	}
	
	public void reset(int i) {
		currentIndex = previous(i);
	}

	public int badtoken() {
		return 0;
	}

	public int kind(int tokenIndex) {
		return token(tokenIndex).kind;
	}
	
	public char[] name(int tokenIndex) {
		return token(tokenIndex).name;
	}

	public int line(int tokenIndex) {
		return token(tokenIndex).line;
	}
	
	public int start(int tokenIndex) {
		return token(tokenIndex).start;
	}
	
	public int end(int tokenIndex) {
		return token(tokenIndex).end;
	}
	
	public int flags(int tokenIndex) {
		return token(tokenIndex).flags;
	}
	
	public boolean isInsideStream(int index) {
		if(this.tokenCacheEOFIndex >= 0 && index > this.tokenCacheEOFIndex) {
			return false;
		} else if(index > this.tokenCacheIndex) {
			return true;
		} else if(this.tokenCacheIndex - tokenCache.length >= index) {
			return false;
		} else {
			return true;
		}
	}
	
	/* (non-Javadoc)
	 * @see java.lang.Object#toString()
	 */
	public String toString() {
		StringBuffer res = new StringBuffer();
		
		String source = new String(scanner.source);
		if(currentIndex < 0) {
			int previousEnd = -1;
			for (int i = 0; i < intervalStartToSkip.length; i++) {
				int intervalStart = intervalStartToSkip[i];
				int intervalEnd = intervalEndToSkip[i];
				
				res.append(source.substring(previousEnd + 1, intervalStart));
				res.append('<');
				res.append('@');
				res.append(source.substring(intervalStart, intervalEnd + 1));
				res.append('@');
				res.append('>');
				
				previousEnd = intervalEnd;
			}
			res.append(source.substring(previousEnd + 1));
		} else {
			Token token = token(currentIndex);
			int curtokKind = token.kind;
			int curtokStart = token.start;
			int curtokEnd = token.end;
			
			int previousEnd = -1;
			for (int i = 0; i < intervalStartToSkip.length; i++) {
				int intervalStart = intervalStartToSkip[i];
				int intervalEnd = intervalEndToSkip[i];
				
				if(curtokStart >= previousEnd && curtokEnd <= intervalStart) {
					res.append(source.substring(previousEnd + 1, curtokStart));
					res.append('<');
					res.append('#');
					res.append(source.substring(curtokStart, curtokEnd + 1));
					res.append('#');
					res.append('>');
					res.append(source.substring(curtokEnd+1, intervalStart));
				} else {
					res.append(source.substring(previousEnd + 1, intervalStart));
				}
				res.append('<');
				res.append('@');
				res.append(source.substring(intervalStart, intervalEnd + 1));
				res.append('@');
				res.append('>');
				
				previousEnd = intervalEnd;
			}
			if(curtokStart >= previousEnd) {
				res.append(source.substring(previousEnd + 1, curtokStart));
				res.append('<');
				res.append('#');
				if(curtokKind == TokenNameEOF) {
					res.append("EOF#>"); //$NON-NLS-1$
				} else {
					res.append(source.substring(curtokStart, curtokEnd + 1));
					res.append('#');
					res.append('>');
					res.append(source.substring(curtokEnd+1));
				}
			} else {
				res.append(source.substring(previousEnd + 1));
			}
		}
		
		return res.toString();
	}
}
