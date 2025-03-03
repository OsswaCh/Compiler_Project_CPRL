package edu.citadel.cprl;

import edu.citadel.common.Position;
import edu.citadel.common.ErrorHandler;
import edu.citadel.common.ParserException;
import edu.citadel.common.FatalException;
import edu.citadel.common.InternalCompilerException;

import java.io.IOException;
import java.util.*;

/**
 * This class uses recursive descent to perform syntax analysis of
 * the CPRL source language.
 */
public final class Parser
  {
    private Scanner scanner;
    private IdTable idTable;
    private ErrorHandler errorHandler;

    //private final EnumSet<Symbol> emptySet = EnumSet.noneOf(Symbol.class);
    
    /**
     * Symbols that can follow a statement.
     */
    private final Set<Symbol> stmtFollowers = EnumSet.of( 
    		Symbol.identifier, Symbol.ifRW, Symbol.elseRW, 
    		Symbol.whileRW, Symbol.loopRW, Symbol.forRW, 
    		Symbol.readRW, Symbol.writelnRW, Symbol.exitRW,
    		Symbol.leftBrace, Symbol.rightBrace, Symbol.returnRW,
    		Symbol.writelnRW
    		); 

    /**
     * Symbols that can follow a subprogram declaration.
     */
    private final Set<Symbol> subprogDeclFollowers = EnumSet.of(
    		Symbol.procRW, Symbol.funRW, Symbol.EOF  
      ); //changed

    /**
     * Symbols that can follow a factor.
     */
    private final Set<Symbol> factorFollowers = EnumSet.of(
        Symbol.semicolon,   Symbol.loopRW,      Symbol.thenRW,
        Symbol.rightParen,  Symbol.andRW,       Symbol.orRW,
        Symbol.equals,      Symbol.notEqual,    Symbol.lessThan,
        Symbol.lessOrEqual, Symbol.greaterThan, Symbol.greaterOrEqual,
        Symbol.plus,        Symbol.minus,       Symbol.times,
        Symbol.divide,      Symbol.modRW,       Symbol.rightBracket,
        Symbol.comma,       Symbol.bitwiseAnd,  Symbol.bitwiseOr,
        Symbol.bitwiseXor,  Symbol.leftShift,   Symbol.rightShift,
        Symbol.dotdot
    );
    
    private final Set<Symbol> relationFollowers = EnumSet.of(
    	Symbol.andRW, Symbol.orRW, 
        Symbol.semicolon, Symbol.thenRW, Symbol.loopRW, 
   	    Symbol.rightParen, Symbol.rightBracket, Symbol.comma 	
    );

    private final Set<Symbol> simpleExprFollowers = EnumSet.of(
		Symbol.equals, Symbol.notEqual, Symbol.lessThan, 
	    Symbol.lessOrEqual, Symbol.greaterThan, Symbol.greaterOrEqual,
	    Symbol.andRW, Symbol.orRW, 
	    Symbol.semicolon, Symbol.thenRW, Symbol.loopRW, 
	    Symbol.rightParen, Symbol.rightBracket, Symbol.comma 		
    );
    /**
     * Symbols that can follow an initial declaration (computed property).
     * Set is computed dynamically based on the scope level.
     */
    private Set<Symbol> initialDeclFollowers()
      {
        // An initial declaration can always be followed by another
        // initial declaration, regardless of the scope level.
        var followers = EnumSet.of(Symbol.constRW, Symbol.varRW, Symbol.typeRW);

        if (idTable.scopeLevel() == ScopeLevel.GLOBAL)
            followers.addAll(EnumSet.of(Symbol.procRW, Symbol.funRW));
        else
          {
            followers.addAll(stmtFollowers);
            followers.remove(Symbol.elseRW);
          }

        return followers;
      }


    /**
     * Construct a parser with the specified scanner, identifier
     * table, and error handler.
     */
    public Parser(Scanner scanner, IdTable idTable, ErrorHandler errorHandler)
      {
        this.scanner = scanner;
        this.idTable = idTable;
        this.errorHandler = errorHandler;
      }

    /**
     * Parse the following grammar rule:<br>
     * <code>program = initialDecls subprogramDecls .</code>
     */
    public void parseProgram() throws IOException
      {
        try
          {
            parseInitialDecls();
            parseSubprogramDecls();
            match(Symbol.EOF);
          }
        catch (ParserException e)
          {
            errorHandler.reportError(e);
            recover(EnumSet.of(Symbol.EOF));
          }
      }

    /**
     * Parse the following grammar rule:<br>
     * <code>initialDecls = { initialDecl } .</code>
     */
    private void parseInitialDecls() throws IOException
      {
        while (scanner.symbol().isInitialDeclStarter())
            parseInitialDecl();
      }

    /**
     * Parse the following grammar rule:<br>
     * <code>initialDecl = constDecl | varDecl | typeDecl .</code>
     */
    private void parseInitialDecl() throws IOException
      {
        	 switch(scanner.symbol())
             {
             case constRW -> parseConstDecl(); 
             case varRW -> parseVarDecl();
             case typeRW -> parseTypeDecl();
             default ->
    	         {
    	        	 throw internalError(scanner.token()+
                         "Expected 'const', 'var', or 'type' but found " + scanner.symbol()); 
    	         }
             }
      }

    /**
     * Parse the following grammar rule:<br>
     * <code>constDecl = "const" constId ":=" [ "-" ] literal ";" .</code>
     */
    private void parseConstDecl() throws IOException
    {
      try
        {
          match(Symbol.constRW);
          var constId = scanner.token();
          match(Symbol.identifier);
          match(Symbol.assign);

          if (scanner.symbol() == Symbol.minus)
              matchCurrentSymbol();

          parseLiteral();
          match(Symbol.semicolon);
          idTable.add(constId, IdType.constantId);
        }
      catch (ParserException e)
        {
          errorHandler.reportError(e);
          recover(initialDeclFollowers());
        }
    }

    /**
     * Parse the following grammar rule:<br>
     * <code>literal = intLiteral | charLiteral | stringLiteral | "true" | "false" .</code>
     */
    private void parseLiteral() throws IOException
    {
      try
        {
          if (scanner.symbol().isLiteral())
              matchCurrentSymbol();
          else
              throw error("Invalid literal expression.");
        }
      catch (ParserException e)
        {
          errorHandler.reportError(e);
          recover(factorFollowers);
        }
    }

    /**
     * Parse the following grammar rule:<br>
     * <code>varDecl = "var" identifiers ":" ( typeName |	 arrayTypeConstr | stringTypeConstr)
     *               [ ":=" initializer] ";" .</code>
     */
    private void parseVarDecl() throws IOException
      {
        try
          { 
            match(Symbol.varRW);
            var identifiers = parseIdentifiers();
            match(Symbol.colon);

            var symbol = scanner.symbol();
            if (symbol.isPredefinedType() || symbol == Symbol.identifier)
                parseTypeName();
            else if (symbol == Symbol.arrayRW)
                parseArrayTypeConstr();
            else if (symbol == Symbol.stringRW)
                 parseStringTypeConstr();
            else
              {
                var errorMsg = "Expecting a type name, reserved word \"array\", "
                             + "or reserved word \"string\".";
                throw error(errorMsg);
              }

            if (scanner.symbol() == Symbol.assign)
              {
                matchCurrentSymbol();
                parseInitializer();
              }

            match(Symbol.semicolon);

            for (Token identifier : identifiers)
                idTable.add(identifier, IdType.variableId);
          }
        catch (ParserException e)
          {
            errorHandler.reportError(e);
            recover(initialDeclFollowers());
          }
      }

    /**
     * Parse the following grammar rule:<br>
     * <code>identifiers = identifier { "," identifier } .</code>
     *
     * @return the list of identifier tokens.  Returns an empty list if parsing fails.
     */
    private List<Token> parseIdentifiers() throws IOException
      {
        try
          {
            var identifiers = new ArrayList<Token>(10);
            var idToken = scanner.token();
            match(Symbol.identifier);
            identifiers.add(idToken);

            while (scanner.symbol() == Symbol.comma)
              {
                matchCurrentSymbol();
                idToken = scanner.token();
                match(Symbol.identifier);
                identifiers.add(idToken);
              }

            return identifiers;
          }
        catch (ParserException e)
          {
            errorHandler.reportError(e);
            recover(EnumSet.of(Symbol.colon, Symbol.greaterThan));
            return Collections.emptyList();   // should never execute
          }
      }

    /**
     * Parse the following grammar rule:<br>
     * <code>initializer = constValue | compositeInitializer .</code>
     */
    private void parseInitializer() throws IOException
      {
        try
          {
            var symbol = scanner.symbol();
            if (symbol == Symbol.identifier || symbol.isLiteral() || symbol == Symbol.minus)
                parseConstValue();
            else if (symbol == Symbol.leftBrace)
                parseCompositeInitializer();
            else
              {
                var errorMsg = "Expecting literal, identifier, or left brace.";
                throw error(errorMsg);
              }
          }
        catch (ParserException e)
          {
            errorHandler.reportError(e);
            recover(initialDeclFollowers());
          }
      }

    /**
     * Parse the following grammar rule:<br>
     * <code>compositeInitializer = "{" initializer { "," initializer } "}" .</code>
     */
    private void parseCompositeInitializer() throws IOException
      {
    	try 
    	{
    		//var symbol = scanner.symbol();
    		match(Symbol.leftBrace);
    		parseInitializer();
    		while (scanner.symbol() == Symbol.comma) 
    		{
                match(Symbol.comma); 
                parseInitializer();
            }
    		match(Symbol.rightBrace);
    		
    	}
    	catch (ParserException e)
    	{
    		errorHandler.reportError(e);
            recover(EnumSet.of(Symbol.semicolon, Symbol.comma, Symbol.rightBrace));
    	}
      }

    /**
     * Parse the following grammar rule:<br>
     * <code>typeDecl = arrayTypeDecl | recordTypeDecl | stringTypeDecl .</code>
     */
    private void parseTypeDecl() throws IOException
      {
        assert scanner.symbol() == Symbol.typeRW;

        try
          {
            switch (scanner.lookahead(4).symbol())
              {
                case Symbol.arrayRW  -> parseArrayTypeDecl();
                case Symbol.recordRW -> parseRecordTypeDecl();
                case Symbol.stringRW -> parseStringTypeDecl();
                default ->
                  {
                    var errorPos = scanner.lookahead(4).position();
                    throw error(errorPos, "Invalid type declaration.");
                  }
              };
          }
        catch (ParserException e)
          {
            errorHandler.reportError(e);
            recover(initialDeclFollowers());
          }
      }

    /**
     * Parse the following grammar rule:<br>
     * <code>arrayTypeDecl = "type" typeId "=" "array" "[" intConstValue "]"
     *                       "of" typeName ";" .</code>
     *                       
     */
    private void parseArrayTypeDecl() throws IOException
      {
    	try
    	{
    		match(Symbol.typeRW);
    		var typeId = scanner.token();
    		match(Symbol.identifier);
            match(Symbol.equals);
            match(Symbol.arrayRW);
            match(Symbol.leftBracket);
            parseConstValue();
            match(Symbol.rightBracket);
            match(Symbol.ofRW);
            parseTypeName();
            match(Symbol.semicolon);
            idTable.add(typeId, IdType.arrayTypeId);
    	}
    	catch (ParserException e)
    	{
    		errorHandler.reportError(e);
            recover(initialDeclFollowers());
    	}
      }

    /**
     * Parse the following grammar rule:<br>
     * <code>arrayTypeConstr = "array" "[" intConstValue "]" "of" typeName .</code>
     */
    private void parseArrayTypeConstr() throws IOException
      {
        try
          {
            match(Symbol.arrayRW);
            match(Symbol.leftBracket);
            parseConstValue();
            match(Symbol.rightBracket);
            match(Symbol.ofRW);
            parseTypeName();

          }
        catch (ParserException e)
          {
            errorHandler.reportError(e);
            recover(EnumSet.of(Symbol.equals, Symbol.semicolon));
          }
      }

    /**
     * Parse the following grammar rule:<br>
     * <code>"type" typeId "=" "record" "{" fieldDecls "}" ";" .</code>
     */
    private void parseRecordTypeDecl() throws IOException
      {
        try
          {
            match(Symbol.typeRW);
            var typeId = scanner.token();
            match(Symbol.identifier);
            match(Symbol.equals);
            match(Symbol.recordRW);
            match(Symbol.leftBrace);

            try
              {
                idTable.openScope(ScopeLevel.RECORD);
                parseFieldDecls();
              }
            finally
              {
                idTable.closeScope();
              }

            match(Symbol.rightBrace);
            match(Symbol.semicolon);
            idTable.add(typeId, IdType.recordTypeId);
          }
        catch (ParserException e)
          {
            errorHandler.reportError(e);
            recover(initialDeclFollowers());
            //check
          }
      }

    /**
     * Parse the following grammar rule:<br>
     * <code>fieldDecls = { fieldDecl } .</code>
     */
    private void parseFieldDecls() throws IOException
      {
    	while (scanner.symbol() == Symbol.identifier)
    		parseFieldDecl();
    	
      }

    /**
     * Parse the following grammar rule:<br>
     * <code>fieldDecl = fieldId ":" typeName ";" .</code>
     */
    private void parseFieldDecl() throws IOException
      {
    	try {
    	var fieldName = scanner.token(); 
        match(Symbol.identifier); 
        match(Symbol.colon); 
        parseTypeName(); 
        match(Symbol.semicolon);
        
        idTable.add(fieldName, IdType.fieldId);
        
    	}
        catch (ParserException e) 
    	{
        	errorHandler.reportError(e);
            recover(EnumSet.of(Symbol.identifier, Symbol.rightBrace));
            //check
        }
      }

    /**
     * Parse the following grammar rule:<br>
     * <code>"type" typeId "=" "string" "[" intConstValue "]" ";" .</code>
     */
    private void parseStringTypeDecl() throws IOException
      {
    	try {
    		match(Symbol.typeRW);
    		var typeId = scanner.token(); 
            match(Symbol.identifier); 
            match(Symbol.equals);
            match(Symbol.stringRW);
            match(Symbol.leftBracket);
            parseConstValue();
            //match(Symbol.intLiteral); 
            match(Symbol.rightBracket);
            match(Symbol.semicolon);

            idTable.add(typeId, IdType.stringTypeId);
    		 
    	}
    	catch (ParserException e)
    	{
        	errorHandler.reportError(e);
            recover(initialDeclFollowers());
    	}
      }

    /**
     * Parse the following grammar rule:<br>
     * <code>stringTypeConstr = "string" "[" intConstValue "]" .</code>
     */
    private void parseStringTypeConstr() throws IOException
      {
        try
          {
            match(Symbol.stringRW);
            match(Symbol.leftBracket);
            parseConstValue();
            match(Symbol.rightBracket);
          }
        catch (ParserException e)
          {
            errorHandler.reportError(e);
            recover(EnumSet.of(Symbol.equals, Symbol.semicolon));
          }
      }

    /**
     * Parse the following grammar rule:<br>
     * <code>typeName = "Integer" | "Boolean" | "Char" | typeId .</code>
     */
    private void parseTypeName() throws IOException
      {
        try
          {
            switch (scanner.symbol())
              {
                case IntegerRW  -> matchCurrentSymbol();
                case BooleanRW  -> matchCurrentSymbol();
                case CharRW     -> matchCurrentSymbol();
                case identifier ->
                  {
                    var typeId = scanner.token();
                    matchCurrentSymbol();
                    var type = idTable.get(typeId.text());

                    if (type != null)
                      {
                        if (type == IdType.arrayTypeId || type == IdType.recordTypeId || type == IdType.stringTypeId)
                            ;   // empty statement for versions 1 and 2 of Parser
                        else
                          {
                            var errorMsg = "Identifier \"" + typeId + "\" is not a valid type name.";
                            throw error(typeId.position(), errorMsg);
                          }
                      }
                    else
                      {
                        var errorMsg = "Identifier \"" + typeId + "\" has not been declared.";
                        throw error(typeId.position(), errorMsg);
                      }
                  }
                default -> throw error("Invalid type name.");
              }
          }
        catch (ParserException e)
          {
            errorHandler.reportError(e);
            recover(EnumSet.of(Symbol.semicolon,  Symbol.comma,
                    Symbol.rightParen, Symbol.leftBrace));
          }
      }

    /**
     * Parse the following grammar rule:<br>
     * <code>subprogramDecls = { subprogramDecl } .</code>
     * @throws ParserException 
     */
    private void parseSubprogramDecls() throws IOException, ParserException
      {
    	while(scanner.symbol().isSubprogramDeclStarter())
    		parseSubprogramDecl() ;   	
      }

    /**
     * Parse the following grammar rule:<br>
     * <code>subprogramDecl = procedureDecl | functionDecl .</code>
     */
    private void parseSubprogramDecl() throws IOException
      {
    	//throw an internal error if the symbol is not one of procRW or funRW
  
		switch (scanner.symbol()) 
		{
            case procRW -> parseProcedureDecl(); 
            case funRW -> parseFunctionDecl();   
            default -> 
            {
            	throw internalError(scanner.token()
                        +"Expected 'proc' or 'fun' but found " + scanner.symbol());
            }
		}	
    	}
    	 
      

    /**
     * Parse the following grammar rule:<br>
     * <code>procedureDecl = "proc" procId "(" [ parameterDecls ] ")"
     *                       "{" initialDecls statements "}" .</code>
     */
    private void parseProcedureDecl() throws IOException
      {
        try
          {
            match(Symbol.procRW);
            var procId = scanner.token();
            match(Symbol.identifier);
            idTable.add(procId, IdType.procedureId);
            match(Symbol.leftParen);

            try
              {
                idTable.openScope(ScopeLevel.LOCAL);

                if (scanner.symbol().isParameterDeclStarter())
                    parseParameterDecls();

                match(Symbol.rightParen);
                match(Symbol.leftBrace);
                parseInitialDecls();
                parseStatements();
              }
            finally
              {
                idTable.closeScope();
              }

            match(Symbol.rightBrace);
          }
        catch (ParserException e)
          {
            errorHandler.reportError(e);
            recover(subprogDeclFollowers);
          }
      }

    /**
     * Parse the following grammar rule:<br>
     * <code>functionDecl = "fun" funcId "(" [ parameterDecls ] ")" ":" typeName
     *                      "{" initialDecls statements "}" .</code>
     */
    private void parseFunctionDecl() throws IOException
      {
    	try {
    		match(Symbol.funRW);
            var funcId = scanner.token();
            match(Symbol.identifier);
            idTable.add(funcId, IdType.functionId);
            match(Symbol.leftParen);
            
            //new scope
            try {
                idTable.openScope(ScopeLevel.LOCAL);
                if (scanner.symbol().isParameterDeclStarter()) {
                    parseParameterDecls();
                }
                match(Symbol.rightParen);
                match(Symbol.colon);
                parseTypeName();
                match(Symbol.leftBrace);
                parseInitialDecls();
                parseStatements();
            } finally {
                idTable.closeScope();
            }
            match(Symbol.rightBrace);
    		
    	}
    	catch (ParserException e)
    	{
    		errorHandler.reportError(e);
    		recover(subprogDeclFollowers);
    	}
      }

    /**
     * Parse the following grammar rule:<br>
     * <code>parameterDecls = parameterDecl { "," parameterDecl } .</code>
     */
    private void parseParameterDecls() throws IOException
      {
 		parseParameterDecl();
 		try 
 		{
	    	while (scanner.symbol() == Symbol.comma) 
	    	{
	        	match(Symbol.comma);
	        	parseParameterDecl(); 
	        }
        }
        catch (ParserException e)
        {
			errorHandler.reportError(e);
            recover(EnumSet.of(Symbol.rightParen)); // make prettier
        }
        	 
      }

    /**
     * Parse the following grammar rule:<br>
     * <code>parameterDecl = [ "VAR" ] paramId ":" typeName .</code>
     */
    private void parseParameterDecl() throws IOException
      {
    	
		try 
		{
			
			if(scanner.symbol() == Symbol.varRW) {
				matchCurrentSymbol();
			}
			var paramId=scanner.token();
			match(Symbol.identifier); 
			match(Symbol.colon);
			parseTypeName();
            idTable.add(paramId, IdType.variableId);
			
		}
		
        catch (ParserException e)
        {
			errorHandler.reportError(e);
            recover(EnumSet.of(Symbol.comma, Symbol.rightParen));
        }
      }

    /**
     * Parse the following grammar rule:<br>
     * <code>statements = { statement } .</code>
     */
    private void parseStatements() throws IOException
      {
    	while(scanner.symbol().isStmtStarter())
    	{
    		parseStatement();
    	}
      }

    /**
     * Parse the following grammar rule:<br>
     * <code>statement = assignmentStmt | procedureCallStmt | compoundStmt | ifStmt
     *                 | loopStmt       | forLoopStmt       | exitStmt     | readStmt
     *                 | writeStmt      | writelnStmt       | returnStmt .</code>
     */
    private void parseStatement() throws IOException
      {
        try
          {
            if (scanner.symbol() == Symbol.identifier)
              {
                // Handle identifiers based on how they are declared,
                // or use the lookahead symbol if not declared.
                var idStr  = scanner.text();
                var idType = idTable.get(idStr);

                if (idType != null)
                  {
                    if (idType == IdType.variableId)
                        parseAssignmentStmt();
                    else if (idType == IdType.procedureId)
                        parseProcedureCallStmt();
                    else
                        throw error("Identifier \"" + idStr + "\" cannot start a statement.");
                  }
                else
                  {
                	//make parsing decision using lookahead symbol
                	if(scanner.lookahead(2).symbol() == Symbol.leftParen)
                		parseProcedureCallStmt();
                	else
                		throw error ("Identifier \"" + idStr + "\" has not been declared.");
                  }
              }
            else
              {
                switch (scanner.symbol())
                  {
                    case Symbol.leftBrace -> parseCompoundStmt();
                    case Symbol.ifRW      -> parseIfStmt();
                    case Symbol.whileRW	  -> parseLoopStmt();
                    case Symbol.loopRW    -> parseLoopStmt();
                    case Symbol.forRW     -> parseForLoopStmt(); 
                    case Symbol.exitRW    -> parseExitStmt();
                    case Symbol.readRW    -> parseReadStmt();
                    case Symbol.writeRW   -> parseWriteStmt();
                    case Symbol.writelnRW -> parseWritelnStmt();
                    case Symbol.returnRW  -> parseReturnStmt();
                    default -> throw internalError(scanner.token()
                                   + " cannot start a statement.");
                  }
              }
          }
        catch (ParserException e)
          {
            errorHandler.reportError(e);
            // Error recovery here is complicated for identifiers since they can both
            // start a statement and appear elsewhere in the statement.  (Consider,
            // for example, an assignment statement or a procedure call statement.)
            // Since the most common error is to declare or reference an identifier
            // incorrectly, we will assume that this is the case and advance to the
            // end of the current statement before performing error recovery.
            scanner.advanceTo(EnumSet.of(Symbol.semicolon, Symbol.rightBrace));
            recover(stmtFollowers);
          }
      }

    /**
     * Parse the following grammar rule:<br>
     * <code>assignmentStmt = variable ":=" expression ";" .</code>
     */
    private void parseAssignmentStmt() throws IOException
      {
    	//check if we need to put in table or not 
    	try {
    		parseVariable();
    		try
    		{
    			match(Symbol.assign);
    		}
    		catch (ParserException e)
    		{
    			if(scanner.symbol() == Symbol.equals)
    			{
    				errorHandler.reportError(e);
    				matchCurrentSymbol();
    			}
    			else
    				throw e;
    		} //added
    		
    		parseExpression();
    		match(Symbol.semicolon);
    	}
        catch (ParserException e)
        {
          errorHandler.reportError(e);
          recover(stmtFollowers);
        }
      }

    /**
     * Parse the following grammar rule:<br>
     * <code>compoundStmt = "{" statements "}" .<\code>
     */
    private void parseCompoundStmt() throws IOException
      {
    	try {
    		match(Symbol.leftBrace);
    		parseStatements();
    		match(Symbol.rightBrace);
    		
    	}
        catch (ParserException e)
        {
          errorHandler.reportError(e);
          recover(stmtFollowers);
        }
      }

    /**
     * Parse the following grammar rule:<br>
     * <code>ifStmt = "if" booleanExpr "then" statement  [ "else" statement ] .</code>
     */
    private void parseIfStmt() throws IOException
      {
    	try
    	{
    	
    		match(Symbol.ifRW);
    		parseExpression();
    		match(Symbol.thenRW);
    		parseStatement();
    		//parse the optional else 
    		if(scanner.symbol() == Symbol.elseRW)
    		{
    			matchCurrentSymbol();
    			parseStatement();
    		}
    		
    		
    	}
    	catch (ParserException e)
        {
          errorHandler.reportError(e);
          recover(stmtFollowers);
        }
      }

    /**
     * Parse the following grammar rule:<br>
     * <code>loopStmt = [ "while" booleanExpr ] "loop" statement .</code>
     */
    private void parseLoopStmt() throws IOException
      {
    	try
    	{
    		if(scanner.symbol() == Symbol.whileRW)
    		{
    			match(Symbol.whileRW);
    			parseExpression(); 
    		}
    		match(Symbol.loopRW);
    		parseStatement();
    	}
    	catch (ParserException e)
        {
          errorHandler.reportError(e);
          recover(stmtFollowers);
        }
      }

    /**
     * Parse the following grammar rule:<br>
     * <code>forLoopStmt = "for" varId "in" intExpr ".." intExpr "loop" statement .</code>
     */
    private void parseForLoopStmt() throws IOException
      {
        try
          {
            // create a new scope for the loop variable
            idTable.openScope(ScopeLevel.LOCAL);

            match(Symbol.forRW);
            var loopId = scanner.token();
            match(Symbol.identifier);
            idTable.add(loopId, IdType.variableId);
            match(Symbol.inRW);
            parseExpression();
            match(Symbol.dotdot);
            parseExpression();
            match(Symbol.loopRW);
            parseStatement();
          }
        catch (ParserException e)
          {
            errorHandler.reportError(e);
            recover(stmtFollowers);
          }
        finally
          {
            idTable.closeScope();
          }
      }

    /**
     * Parse the following grammar rule:<br>
     * <code>exitStmt = "exit" [ "when" booleanExpr ] ";" .</code>
     */
    private void parseExitStmt() throws IOException
      {
    	try
    	{
    		match(Symbol.exitRW);
    		if(scanner.symbol() == Symbol.whenRW) 
    		{
    			match(Symbol.whenRW);
    			parseExpression();
    		}
    		match(Symbol.semicolon);
    		
    	}
        catch (ParserException e)
        {
          errorHandler.reportError(e);
          recover(stmtFollowers);
        }
      }

    /**
     * Parse the following grammar rule:<br>
     * <code>readStmt = "read" variable ";" .</code>
     */
    private void parseReadStmt() throws IOException
      {
    	try
    	{
    		match(Symbol.readRW);
    		parseVariable();
    		match(Symbol.semicolon);
    	}
    	catch (ParserException e)
        {
          errorHandler.reportError(e);
          recover(stmtFollowers);
        }
      }

    /**
     * Parse the following grammar rule:<br>
     * <code>writeStmt = "write" expressions ";" .</code>
     */
    private void parseWriteStmt() throws IOException
      {
    	try
    	{
    		match(Symbol.writeRW);
    		parseExpressions();
    		match(Symbol.semicolon);
    	}
    	catch (ParserException e)
        {
          errorHandler.reportError(e);
          recover(stmtFollowers);
        }
      }

    /**
     * Parse the following grammar rule:<br>
     * <code>expressions = expression { "," expression } .</code>
     */
    private void parseExpressions() throws IOException
      {
    	parseExpression();
	    	while (scanner.symbol() == Symbol.comma) 
	    	{
	        	matchCurrentSymbol();
	        	parseExpression();
	        }

      }

    /**
     * Parse the following grammar rule:<br>
     * <code>writelnStmt = "writeln" [ expressions ] ";" .</code>
     */
    private void parseWritelnStmt() throws IOException
      {
        try
          {
            match(Symbol.writelnRW);

            if (scanner.symbol().isExprStarter())
                parseExpressions();

            match(Symbol.semicolon);
          }
        catch (ParserException e)
          {
            errorHandler.reportError(e);
            recover(stmtFollowers);
          }
      }

    /**
     * Parse the following grammar rule:<br>
     * <code>procedureCallStmt = procId "(" [ actualParameters ] ")" ";" .
     *       actualParameters = expressions .</code>
     */
    private void parseProcedureCallStmt() throws IOException
      {
    	try
    	{
            match(Symbol.identifier);
            match(Symbol.leftParen);
            if(scanner.symbol().isExprStarter())
            {
            	parseExpressions();
            	//check
            }
            match(Symbol.rightParen);
            match(Symbol.semicolon);
    		
    	}
    	catch (ParserException e)
        {
          errorHandler.reportError(e);
          recover(stmtFollowers);
        }
      }

    /**
     * Parse the following grammar rule:<br>
     * <code>returnStmt = "return" [ expression ] ";" .</code>
     */
    private void parseReturnStmt() throws IOException
      {
    	try
    	{
    		match(Symbol.returnRW);
    		if(scanner.symbol().isExprStarter())
    		{
    			parseExpression();
    			//check this
    		}
    		match(Symbol.semicolon);
    	}
    	catch (ParserException e)
        {
          errorHandler.reportError(e);
          recover(stmtFollowers);
        }
      }

    /**
     * Parse the following grammar rules:<br>
     * <code>variable = ( varId | paramId ) { indexExpr | fieldExpr } .<br>
     *       indexExpr = "[" expression "]" .<br>
     *       fieldExpr = "." fieldId .</code>
     * <br>
     * parseVariableExpr().  The method does not handle any ParserExceptions but
     * throws them back to the calling method where they can be handled appropriately.
     *
     * @throws ParserException if parsing fails.
     * @see #parseVariable()
     * @see #parseVariableExpr()
     */
    private void parseVariableCommon() throws IOException, ParserException
      {
        var idToken = scanner.token();
        match(Symbol.identifier);
        var idType = idTable.get(idToken.text());

        if (idType == null)
          {
            var errorMsg = "Identifier \"" + idToken + "\" has not been declared.";
            throw error(idToken.position(), errorMsg);
          }
        else if (idType != IdType.variableId)
          {
            var errorMsg = "Identifier \"" + idToken + "\" is not a variable.";
            throw error(idToken.position(), errorMsg);
          }

        while (scanner.symbol().isSelectorStarter())
          {
            if (scanner.symbol() == Symbol.leftBracket)
              {
                // parse index expression
                match(Symbol.leftBracket);
                parseExpression();
                match(Symbol.rightBracket);
              }
            else if (scanner.symbol() == Symbol.dot)
              {
                // parse field expression
                match(Symbol.dot);
                match(Symbol.identifier);
              }
          }
      }

    /**
     * Parse the following grammar rule:<br>
     * <code>variable = ( varId | paramId ) { indexExpr | fieldExpr } .</code>
     */
    private void parseVariable() throws IOException
      {
        try
          {
            parseVariableCommon();
          }
        catch (ParserException e)
          {
            errorHandler.reportError(e);
            recover(EnumSet.of(Symbol.assign, Symbol.semicolon));
          }
      }

    /**
     * Parse the following grammar rules:<br>
     * <code>expression = relation { logicalOp relation } .<br>
     *        logicalOp = "and" | "or" .</code>
     */
    private void parseExpression() throws IOException
      {
        parseRelation();

        while (scanner.symbol().isLogicalOperator())
          {
            matchCurrentSymbol();
            parseRelation();
          }
      }

    /**
     * Parse the following grammar rules:<br>
     * <code>relation = simpleExpr [ relationalOp simpleExpr ] .<br>
     *   relationalOp = "=" | "!=" | "&lt;" | "&lt;=" | "&gt;" | "&gt;=" .</code>
     */    
    private void parseRelation() throws IOException
      {
    	parseSimpleExpr();
        if (scanner.symbol().isRelationalOperator()) {
            matchCurrentSymbol();
            parseSimpleExpr();
        }
  
        
      }

    /**
     * Parse the following grammar rules:<br>
     * <code>simpleExpr = [ signOp ] term { addingOp term } .<br>
     *       signOp = "+" | "-" .<br>
     *      S.</code>
     */
    private void parseSimpleExpr() throws IOException
      {
        if (scanner.symbol().isAddingOperator()) 
        {
        	matchCurrentSymbol();
        }
        parseTerm();

        while (scanner.symbol().isAddingOperator()) 
        {
        	matchCurrentSymbol(); 
            parseTerm(); 
        }
      }

    /**
     * Parse the following grammar rules:<br>
     * <code>term = factor { multiplyingOp factor } .<br>
     *       multiplyingOp = "*" | "/" | "mod" | "&" | "<<" | ">>" .</code>
     */

    private void parseTerm() throws IOException
      {
        parseFactor();

        while (scanner.symbol().isMultiplyingOperator()) {
            matchCurrentSymbol(); 
            parseFactor(); 
        }
          }

    /**
     * Parse the following grammar rule:<br>
     * <code>factor = ("not" | "~") factor | literal | constId | variableExpr
     *              | functionCallExpr | "(" expression ")" .</code>
     */

    private void parseFactor() throws IOException
      {
        try
          {
            var symbol = scanner.symbol();

            if (symbol == Symbol.notRW || symbol == Symbol.bitwiseNot)
              {
                matchCurrentSymbol();
                parseFactor();
              }
            else if (symbol.isLiteral())
              {
                // Handle constant literals separately from constant identifiers.
                parseConstValue();
              }
            else if (symbol == Symbol.identifier)
              {
                // Three possible cases: a declared constant, a variable
                // expression, or a function call expression.  Use lookahead
                // tokens and declaration to determine correct parsing action.

                var idStr  = scanner.text();
                var idType = idTable.get(idStr);

                if (idType != null)
                  {
                    if (idType == IdType.constantId)
                        parseConstValue();
                    else if (idType == IdType.variableId)
                        parseVariableExpr();
                    else if (idType == IdType.functionId)
                        parseFunctionCallExpr();
                    else
                        throw error("Identifier \"" + idStr
                                  + "\" is not valid as an expression.");
                  }
                else
                  {
                    // Make parsing decision using an additional lookahead symbol.
                    if (scanner.lookahead(2).symbol() == Symbol.leftParen)
                        parseFunctionCallExpr();
                    else
                        throw error("Identifier \"" + scanner.token()
                                  + "\" has not been declared.");
                  }
              }
            else if (symbol == Symbol.leftParen)
              {
                matchCurrentSymbol();
                parseExpression();
                match(Symbol.rightParen);
              }
            else
                throw error("Invalid expression.");
          }
        catch (ParserException e)
          {
            errorHandler.reportError(e);
            recover(factorFollowers);
          }
      }

    /**
     * Parse the following grammar rule:<br>
     * <code>constValue = ( [ "-"] literal ) | constId .</code>
     */
    private void parseConstValue() throws IOException
    {
      try
        {
          if (scanner.symbol().isLiteral())
              parseLiteral();
          else if (scanner.symbol() == Symbol.minus
              && scanner.lookahead(2).symbol() == Symbol.intLiteral)
            {
              // handle negative integer literals as a special case
              match(Symbol.minus);
              match(Symbol.intLiteral);
            }
          else if (scanner.symbol() == Symbol.identifier)
              matchCurrentSymbol();
          else
              throw error("Invalid constant.");
        }
      catch (ParserException e)
        {
          errorHandler.reportError(e);
          recover(EnumSet.of(Symbol.semicolon, Symbol.comma, Symbol.rightBrace, Symbol.rightBracket));
        }
    }

    /**
     * Parse the following grammar rule:<br>
     * <code>variableExpr = variable .</code>
     */
    private void parseVariableExpr() throws IOException
      {
        try
          {
            parseVariableCommon();
          }
        catch (ParserException e)
          {
            errorHandler.reportError(e);
            recover(factorFollowers);
          }
      }

    /**
     * Parse the following grammar rule:<br>
     * <code>functionCallExpr = funcId "(" [ actualParameters ] ")" .
     *       actualParameters = expressions .</code>
     */
    private void parseFunctionCallExpr() throws IOException
      {
        try {
            match(Symbol.identifier);

            match(Symbol.leftParen);
            if (scanner.symbol().isExprStarter()) {
                parseExpressions(); 
            }
            match(Symbol.rightParen);

        } catch (ParserException e) {
            errorHandler.reportError(e);
            recover(factorFollowers);
        }
      }

    /**
     * Check that the current scanner symbol is the expected symbol.  If it
     * is, then advance the scanner.  Otherwise, throw a ParserException.
     */
    private void match(Symbol expectedSymbol) throws IOException, ParserException
      {
        if (scanner.symbol() == expectedSymbol)
            scanner.advance();
        else
          {
            var errorMsg = "Expecting \"" + expectedSymbol + "\" but found \""
                         + scanner.token() + "\" instead.";
            throw error(errorMsg);
          }
      }

    /**
     * Advance the scanner.  This method represents an unconditional match
     * with the current scanner symbol.
     */
    private void matchCurrentSymbol() throws IOException
      {
        scanner.advance();
      }

    /**
     * Advance the scanner until the current symbol is one of the
     * symbols in the specified set of follows.
     */
    private void recover(Set<Symbol> followers) throws IOException
      {
        // no error recovery for version 1 of the parser
        //throw new FatalException("No error recovery -- parsing terminated.");
    	scanner.advanceTo(followers);
      }

    /**
     * Create a parser exception with the specified error message and
     * the current scanner position.
     */
    private ParserException error(String errorMsg)
      {
        return error(scanner.position(), errorMsg);
      }

    /**
     * Create a parser exception with the specified error position and message.
     */
    private ParserException error(Position errorPos, String errorMsg)
      {
        return new ParserException(errorPos, errorMsg);
      }

    /**
     * Create an internal compiler exception with the specified error
     * message and the current scanner position.
     */
    private InternalCompilerException internalError(String errorMsg)
      {
        return new InternalCompilerException(scanner.position(), errorMsg);
      }
  }
