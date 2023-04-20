package CodeGenerator;

import AST.*;
import Utilities.Error;
import Utilities.Visitor;

import java.util.*;

import Instruction.*;
import Jasmin.*;

class GenerateCode extends Visitor {

	private Generator gen;
	private ClassDecl currentClass;
	private boolean insideLoop = false;
	private boolean insideSwitch = false;
	private ClassFile classFile;
	private boolean RHSofAssignment = false;
	private boolean StringBuilderCreated = false;
	
	
	public GenerateCode(Generator g, boolean debug) {
		gen = g;
		this.debug = debug;
		classFile = gen.getClassFile();
	}

	public void setCurrentClass(ClassDecl cd) {
		this.currentClass = cd;
	}

	// ARRAY VISITORS START HERE

	/** ArrayAccessExpr */
	public Object visitArrayAccessExpr(ArrayAccessExpr ae) {
		println(ae.line + ": Visiting ArrayAccessExpr");
		classFile.addComment(ae, "ArrayAccessExpr");
		// YOUR CODE HERE
		classFile.addComment(ae,"End ArrayAccessExpr");
		return null;
	}

	/** ArrayLiteral */
	public Object visitArrayLiteral(ArrayLiteral al) {
		println(al.line + ": Visiting an ArrayLiteral ");
		// YOUR CODE HERE
		return null;
	}

	/** NewArray */
	public Object visitNewArray(NewArray ne) {
		println(ne.line + ": NewArray:\t Creating new array of type " + ne.type.typeName());
		// YOUR CODE HERE
		return null;
	}

	// END OF ARRAY VISITORS

	// ASSIGNMENT
	public Object visitAssignment(Assignment as) {
		println(as.line + ": Assignment:\tGenerating code for an Assignment.");
		classFile.addComment(as, "Assignment");
		/* If a reference is needed then compute it
	          (If array type then generate reference to the	target & index)
	          - a reference is never needed if as.left() is an instance of a NameExpr
	          - a reference can be computed for a FieldRef by visiting the target
	          - a reference can be computed for an ArrayAccessExpr by visiting its target 
		 */
		if (as.left() instanceof FieldRef) {
			println(as.line + ": Generating reference for FieldRef target ");
			FieldRef fr= (FieldRef)as.left();
			fr.target().visit(this);		
			// if the target is a New and the field is static, then the reference isn't needed, so pop it! 
			if (fr.myDecl.isStatic()) // && fr.target() instanceof New) // 3/10/2017 - temporarily commented out
			    // issue pop if target is NOT a class name.
			    if (fr.target() instanceof NameExpr && (((NameExpr)fr.target()).myDecl instanceof ClassDecl))
				;
			    else
				classFile.addInstruction(new Instruction(RuntimeConstants.opc_pop));			
		} else if (as.left() instanceof ArrayAccessExpr) {
			println(as.line + ": Generating reference for Array Access target");
			ArrayAccessExpr ae = (ArrayAccessExpr)as.left();
			classFile.addComment(as, "ArrayAccessExpr target");
			ae.target().visit(this);
			classFile.addComment(as, "ArrayAccessExpr index");
			ae.index().visit(this);
		}

		/* If the assignment operator is <op>= then
	            -- If the left hand side is a non-static field (non array): dup (object ref) + getfield
	            -- If the left hand side is a static field (non array): getstatic   
	            -- If the left hand side is an array reference: dup2 +	Xaload 
				-- If the left hand side is a local (non array): generate code for it: Xload Y 
		 */	        
		if (as.op().kind != AssignmentOp.EQ) {
			if (as.left() instanceof FieldRef) {
				println(as.line + ": Duplicating reference and getting value for LHS (FieldRef/<op>=)");
				FieldRef fr = (FieldRef)as.left();
				if (!fr.myDecl.isStatic()) {
					classFile.addInstruction(new Instruction(RuntimeConstants.opc_dup));
					classFile.addInstruction(new FieldRefInstruction(RuntimeConstants.opc_getfield, fr.targetType.typeName(),
							fr.fieldName().getname(), fr.type.signature()));
				} else 
					classFile.addInstruction(new FieldRefInstruction(RuntimeConstants.opc_getstatic, fr.targetType.typeName(),
							fr.fieldName().getname(), fr.type.signature()));
			} else if (as.left() instanceof ArrayAccessExpr) {
				println(as.line + ": Duplicating reference and getting value for LHS (ArrayAccessRef/<op>=)");
				ArrayAccessExpr ae = (ArrayAccessExpr)as.left();
				classFile.addInstruction(new Instruction(RuntimeConstants.opc_dup2));
				classFile.addInstruction(new Instruction(Generator.getArrayLoadInstruction(ae.type)));
			} else { // NameExpr
				println(as.line + ": Getting value for LHS (NameExpr/<op>=)");
				NameExpr ne = (NameExpr)as.left();
				int address = ((VarDecl)ne.myDecl).address();

				if (address < 4)
					classFile.addInstruction(new Instruction(Generator.getLoadInstruction(((VarDecl)ne.myDecl).type(), address, true)));
				else
					classFile.addInstruction(new SimpleInstruction(Generator.getLoadInstruction(((VarDecl)ne.myDecl).type(), address, true), address));
			}
		}

		/* Visit the right hand side (RHS) */
		boolean oldRHSofAssignment = RHSofAssignment;
		RHSofAssignment = true;
		as.right().visit(this);
		RHSofAssignment = oldRHSofAssignment;
		/* Convert the right hand sides type to that of the entire assignment */

		if (as.op().kind != AssignmentOp.LSHIFTEQ &&
		    as.op().kind != AssignmentOp.RSHIFTEQ &&
		    as.op().kind != AssignmentOp.RRSHIFTEQ)
		    gen.dataConvert(as.right().type, as.type);

		/* If the assignment operator is <op>= then
				- Execute the operator
		 */
		if (as.op().kind != AssignmentOp.EQ)
			classFile.addInstruction(new Instruction(Generator.getBinaryAssignmentOpInstruction(as.op(), as.type)));

		/* If we are the right hand side of an assignment
		     -- If the left hand side is a non-static field (non array): dup_x1/dup2_x1
			 -- If the left hand side is a static field (non array): dup/dup2
			 -- If the left hand side is an array reference: dup_x2/dup2_x2 
			 -- If the left hand side is a local (non array): dup/dup2 
		 */    
		if (RHSofAssignment) {
			String dupInstString = "";
			if (as.left() instanceof FieldRef) {
				FieldRef fr = (FieldRef)as.left();
				if (!fr.myDecl.isStatic())  
					dupInstString = "dup" + (fr.type.width() == 2 ? "2" : "") + "_x1";
				else 
					dupInstString = "dup" + (fr.type.width() == 2 ? "2" : "");
			} else if (as.left() instanceof ArrayAccessExpr) {
				ArrayAccessExpr ae = (ArrayAccessExpr)as.left();
				dupInstString = "dup" + (ae.type.width() == 2 ? "2" : "") + "_x2";
			} else { // NameExpr
				NameExpr ne = (NameExpr)as.left();
				dupInstString = "dup" + (ne.type.width() == 2 ? "2" : "");
			}
			classFile.addInstruction(new Instruction(Generator.getOpCodeFromString(dupInstString)));
		}

		/* Store
		     - If LHS is a field: putfield/putstatic
			 -- if LHS is an array reference: Xastore 
			 -- if LHS is a local: Xstore Y
		 */
		if (as.left() instanceof FieldRef) {
			FieldRef fr = (FieldRef)as.left();
			if (!fr.myDecl.isStatic()) 
				classFile.addInstruction(new FieldRefInstruction(RuntimeConstants.opc_putfield,
						fr.targetType.typeName(), fr.fieldName().getname(), fr.type.signature()));
			else 
				classFile.addInstruction(new FieldRefInstruction(RuntimeConstants.opc_putstatic,
						fr.targetType.typeName(), fr.fieldName().getname(), fr.type.signature()));
		} else if (as.left() instanceof ArrayAccessExpr) {
			ArrayAccessExpr ae = (ArrayAccessExpr)as.left();
			classFile.addInstruction(new Instruction(Generator.getArrayStoreInstruction(ae.type)));
		} else { // NameExpr				
			NameExpr ne = (NameExpr)as.left();
			int address = ((VarDecl)ne.myDecl).address() ;

			// CHECK!!! TODO: changed 'true' to 'false' in these getStoreInstruction calls below....
			if (address < 4)
				classFile.addInstruction(new Instruction(Generator.getStoreInstruction(((VarDecl)ne.myDecl).type(), address, false)));
			else {
				classFile.addInstruction(new SimpleInstruction(Generator.getStoreInstruction(((VarDecl)ne.myDecl).type(), address, false), address));
			}
		}
		classFile.addComment(as, "End Assignment");
		return null;
	}

	// BINARY EXPRESSION
    public Object visitBinaryExpr(BinaryExpr be) {
		println(be.line + ": BinaryExpr:\tGenerating code for " + be.op().operator() + " :  " + be.left().type.typeName() + " -> " + be.right().type.typeName() + " -> " + be.type.typeName() + ".");
		classFile.addComment(be, "Binary Expression");

		// YOUR CODE HERE

		//For the relational operators, convert left and right to
		//ceiling type for numerics

		//Don't do any data converts on the shifts

		boolean operatorFound = false;
		int[] arithmeticOperators = {BinOp.PLUS, BinOp.MINUS, BinOp.MULT,
									 BinOp.DIV, BinOp.MOD, BinOp.AND,
									 BinOp.OR, BinOp.XOR};

		for (int i = 0; i < 8; i++){
			if (be.op().kind == arithmeticOperators[i]){
				be.left().visit(this);
				Type leftType = be.left().type;
				gen.dataConvert(leftType, be.type);

				be.right().visit(this);
				Type rightType = be.right().type;
				gen.dataConvert(rightType, be.type);

				String typePrefix = be.type.getTypePrefix();
				String operationPostfix = null;

				switch(be.op().kind) {
					case BinOp.PLUS: {
						operationPostfix = "add";
						break;
					}
					case BinOp.MINUS: {
						operationPostfix = "sub";
						break;
					}
					case BinOp.MULT: {
						operationPostfix = "mul";
						break;
					}
					case BinOp.DIV: {
						operationPostfix = "div";
						break;
					}
					case BinOp.MOD: {
						operationPostfix = "rem";
						break;
					}
					case BinOp.AND: {
						//BITWISE OPERATORS CAN GO WITH ARITHMETIC OPERATORS (INCLUDING DATA CONVERSION)
						operationPostfix = "and";
						break;
					}
					case BinOp.OR: {
						operationPostfix = "or";
						break;
					}
					case BinOp.XOR: {
						operationPostfix = "xor";
						break;
					}
				}
				Instruction newInstruction = new Instruction(gen.getOpCodeFromString(typePrefix + operationPostfix));
				classFile.addInstruction(newInstruction);
				operatorFound = true;
				break;
			}
		}

		int[] shiftOperators = {BinOp.LSHIFT, BinOp.RSHIFT, BinOp.RRSHIFT};

		if(!operatorFound) {
			for (int i = 0; i < 3; i++) {
				if (be.op().kind == shiftOperators[i]) {
					PrimitiveType convertType = PrimitiveType.ceilingType((PrimitiveType)be.left().type, (PrimitiveType)be.right().type);

					be.left().visit(this);
					Type leftType = be.left().type;
					gen.dataConvert(leftType, convertType);

					be.right().visit(this);
					Type rightType = be.right().type;
					gen.dataConvert(rightType, convertType);

					String typePrefix = convertType.getTypePrefix();
					String operationPostfix = null;

					switch(be.op().kind){
						case BinOp.LSHIFT: {
							operationPostfix = "shl";
							break;
						}
						case BinOp.RSHIFT: {
							operationPostfix = "shr";
							break;
						}
						case BinOp.RRSHIFT: {
							operationPostfix = "ushr";
							break;
						}
					}
					Instruction newInstruction = new Instruction(gen.getOpCodeFromString(typePrefix + operationPostfix));
					classFile.addInstruction(newInstruction);
					operatorFound = true;
					break;
				}
			}
		}

		int[] relationalOperators = {BinOp.LT, BinOp.GT, BinOp.LTEQ, BinOp.GTEQ,
									 BinOp.EQEQ, BinOp.NOTEQ};

		if (!operatorFound){
			for(int i = 0; i < 6; i++){
				if (be.op().kind == relationalOperators[i]){
					if (be.left().type.isClassType() && be.right().type.isClassType()){

						//REFERENCE INCREMENTS LABEL ONE EXTRA, NOT SURE WHY
						//String extraLabel = "L" + gen.getLabel();

						String trueLabel = "L" + gen.getLabel();
						String doneLabel = "L" + gen.getLabel();

						be.left().visit(this);
						be.right().visit(this);

						switch(be.op().kind){
							case BinOp.EQEQ: {
								classFile.addInstruction(new JumpInstruction(RuntimeConstants.opc_if_acmpeq, trueLabel));
								break;
							}
							default: {
								classFile.addInstruction(new JumpInstruction(RuntimeConstants.opc_if_acmpne, trueLabel));
							}
						}

						classFile.addInstruction(new Instruction(RuntimeConstants.opc_iconst_0));
						classFile.addInstruction(new JumpInstruction(RuntimeConstants.opc_goto, doneLabel));
						classFile.addInstruction(new LabelInstruction(RuntimeConstants.opc_label, trueLabel));
						classFile.addInstruction(new Instruction(RuntimeConstants.opc_iconst_1));
						classFile.addInstruction(new LabelInstruction(RuntimeConstants.opc_label, doneLabel));

					}
					//ASK STRING COMPARISON
					else if(be.left().type.isStringType()){
						be.left().visit(this);
						be.right().visit(this);
						classFile.addInstruction(new MethodInvocationInstruction(RuntimeConstants.opc_invokevirtual,
								"java/lang/String", "equals", "(Ljava/lang/Object;)Z"));

						if(be.op().kind == BinOp.NOTEQ){
							classFile.addInstruction(new Instruction(RuntimeConstants.opc_iconst_1));
							classFile.addInstruction(new Instruction(RuntimeConstants.opc_ixor));
						}
					}
					else if(be.left().type.isNullType() || be.right().type.isNullType()){
						String trueLabel = "L" + gen.getLabel();
						String doneLabel = "L" + gen.getLabel();

						if(be.right().type.isNullType()) {
							be.left().visit(this);
						}
						else {
							be.right().visit(this);
						}

						switch(be.op().kind){
							case BinOp.EQEQ: {
								classFile.addInstruction(new JumpInstruction(RuntimeConstants.opc_ifnull, trueLabel));
								break;
							}
							default: {
								classFile.addInstruction(new JumpInstruction(RuntimeConstants.opc_ifnonnull, trueLabel));
							}
						}

						classFile.addInstruction(new Instruction(RuntimeConstants.opc_iconst_0));
						classFile.addInstruction(new JumpInstruction(RuntimeConstants.opc_goto, doneLabel));
						classFile.addInstruction(new LabelInstruction(RuntimeConstants.opc_label, trueLabel));
						classFile.addInstruction(new Instruction(RuntimeConstants.opc_iconst_1));
						classFile.addInstruction(new LabelInstruction(RuntimeConstants.opc_label, doneLabel));
					}
					else{
						String trueLabel = "L" + gen.getLabel();
						String doneLabel = "L" + gen.getLabel();

						PrimitiveType convertType = PrimitiveType.ceilingType((PrimitiveType)be.left().type, (PrimitiveType)be.right().type);

						be.left().visit(this);
						Type leftType = be.left().type;
						gen.dataConvert(leftType, convertType);

						be.right().visit(this);
						Type rightType = be.right().type;
						gen.dataConvert(rightType, convertType);

						String typePrefix = convertType.getTypePrefix();
						String operationPostfix = null;

						switch(be.op().kind){
							case BinOp.LT: {
								operationPostfix = "lt";
								break;
							}
							case BinOp.GT: {
								operationPostfix = "gt";
								break;
							}
							case BinOp.LTEQ: {
								operationPostfix = "le";
								break;
							}
							case BinOp.GTEQ: {
								operationPostfix = "ge";
								break;
							}
							case BinOp.EQEQ: {
								operationPostfix = "eq";
								break;
							}
							case BinOp.NOTEQ: {
								operationPostfix = "ne";
								break;
							}
						}

						if(convertType.isLongType()){
							classFile.addInstruction(new Instruction(gen.getOpCodeFromString(typePrefix + "cmp")));
						}
						else if(convertType.isFloatType() || convertType.isDoubleType()){
							classFile.addInstruction(new Instruction(gen.getOpCodeFromString(typePrefix + "cmpg")));
						}

						String baseInstruction = null;
						if(convertType.isIntegerType()){
							baseInstruction = "if_icmp";
						}
						//ASK INCLUDE PREFIX FOR TYPE e.g. "if_lcmp" for long instead of "if_cmp"
						else{
							baseInstruction = "if";
						}

						classFile.addInstruction(new JumpInstruction(gen.getOpCodeFromString(baseInstruction + operationPostfix), trueLabel));

						classFile.addInstruction(new Instruction(RuntimeConstants.opc_iconst_0));
						classFile.addInstruction(new JumpInstruction(RuntimeConstants.opc_goto, doneLabel));
						classFile.addInstruction(new LabelInstruction(RuntimeConstants.opc_label, trueLabel));
						classFile.addInstruction(new Instruction(RuntimeConstants.opc_iconst_1));
						classFile.addInstruction(new LabelInstruction(RuntimeConstants.opc_label, doneLabel));
						break;
					}

					operatorFound = true;
					break;
				}
			}

		}

		int[] logicalOperators = {BinOp.ANDAND, BinOp.OROR};

		if(!operatorFound){
			for (int i = 0; i < 2; i++){
				if(be.op().kind == logicalOperators[i]){
					be.left().visit(this);
					String finishLabel = "L" + gen.getLabel();
					classFile.addInstruction(new Instruction(RuntimeConstants.opc_dup));
					int comparisonInstructionCode = -1;
					switch(be.op().kind){
						case BinOp.ANDAND: {
							comparisonInstructionCode = RuntimeConstants.opc_ifeq;
							break;
						}
						case BinOp.OROR: {
							comparisonInstructionCode = RuntimeConstants.opc_ifne;
							break;
						}
					}

					classFile.addInstruction(new JumpInstruction(comparisonInstructionCode, finishLabel));
					classFile.addInstruction(new Instruction(RuntimeConstants.opc_pop));
					be.right().visit(this);
					classFile.addInstruction(new LabelInstruction(RuntimeConstants.opc_label, finishLabel));

					break;
				}
			}
		}

		if(be.op().kind == BinOp.INSTANCEOF){
			//Visit left, don't visit right
			be.left().visit(this);
			ClassDecl rightClassDecl = (ClassDecl)((NameExpr)be.right()).myDecl;
			ClassRefInstruction newClassRefInstruction = new ClassRefInstruction(RuntimeConstants.opc_instanceof, rightClassDecl.name());
			classFile.addInstruction(newClassRefInstruction);
		}


		classFile.addComment(be, "End BinaryExpr");
		return null;
    }

    // BREAK STATEMENT
    public Object visitBreakStat(BreakStat br) {
	println(br.line + ": BreakStat:\tGenerating code.");
		classFile.addComment(br, "Break Statement");

		// YOUR CODE HERE
		String currBreakLabel = gen.getBreakLabel();
		classFile.addInstruction(new JumpInstruction(RuntimeConstants.opc_goto, currBreakLabel));

		classFile.addComment(br, "End BreakStat");
		return null;
    }

    // CAST EXPRESSION
    public Object visitCastExpr(CastExpr ce) {
		println(ce.line + ": CastExpr:\tGenerating code for a Cast Expression.");
		classFile.addComment(ce, "Cast Expression");
		String instString;
		// YOUR CODE HERE
		//ASK

		//cast between primitive numerics: use data convert, if it's the same type (e.g. integer to integer, string to string) then do nothing
		//casting up: don't have to checkcast, check with isSuper in Type
		//casting down: you do

		ce.expr().visit(this);

		if(ce.expr().type.isPrimitiveType()){
			if(((PrimitiveType)ce.expr().type).getKind() != ((PrimitiveType)ce.type()).getKind()){
				gen.dataConvert(ce.expr().type, ce.type());
				if(ce.type().isByteType()){
					classFile.addInstruction(new Instruction(RuntimeConstants.opc_i2b));
				}
				else if(ce.type().isShortType()){
					classFile.addInstruction(new Instruction(RuntimeConstants.opc_i2s));
				}
				else if(ce.type().isCharType()){
					classFile.addInstruction(new Instruction(RuntimeConstants.opc_i2c));
				}
			}

		}

		if(ce.expr().type.isClassType()){
			if (Type.isSuper((ClassType)ce.expr().type, (ClassType)ce.type())){
				classFile.addInstruction(new ClassRefInstruction(RuntimeConstants.opc_checkcast, ((ClassType)ce.type()).typeName()));
			}
		}


		classFile.addComment(ce, "End CastExpr");
		return null;
    }
    
	// CONSTRUCTOR INVOCATION (EXPLICIT)
	public Object visitCInvocation(CInvocation ci) {
		println(ci.line + ": CInvocation:\tGenerating code for Explicit Constructor Invocation.");     
		classFile.addComment(ci, "Explicit Constructor Invocation");

		// YOUR CODE HERE
		//visit target by aload_0 since target is always "this"

		classFile.addInstruction(new Instruction(RuntimeConstants.opc_aload_0));

		for(int i = 0; i < ci.args().nchildren; i++){
			ci.args().children[i].visit(this);
			if (ci.args().children[i] instanceof NameExpr) {
				NameExpr currArg = (NameExpr) ci.args().children[i];
				if (currArg.myDecl instanceof VarDecl) {
					gen.dataConvert(((VarDecl) currArg.myDecl).type(), ((ParamDecl) ci.constructor.params().children[i]).type());
				}
			}
			else if(ci.args().children[i] instanceof Literal){
				gen.dataConvert(new PrimitiveType(((Literal) ci.args().children[i]).getKind()), ((ParamDecl) ci.constructor.params().children[i]).type());
			}
		}

		String className = null;
		if(ci.thisConstructorCall()){
			className = ci.targetClass.name();
		}
		//NULL POINTER EXCEPTION HERE????
		//else if(ci.superConstructorCall() && (currentClass.superClass() != null)){
		else{
			className = currentClass.superClass().myDecl.name();
		}


		//Ask method name and param signature
		classFile.addInstruction(new MethodInvocationInstruction(RuntimeConstants.opc_invokespecial, className, "<init>", "(" + ci.constructor.paramSignature() + ")" + "V"));

		classFile.addComment(ci, "End CInvocation");
		return null;
	}

	// CLASS DECLARATION
	public Object visitClassDecl(ClassDecl cd) {
		println(cd.line + ": ClassDecl:\tGenerating code for class '" + cd.name() + "'.");

		// We need to set this here so we can retrieve it when we generate
		// field initializers for an existing constructor.
		currentClass = cd;
		// YOUR CODE HERE
		//loop through body twice: first time visit FieldDecls, second time NOT FieldDecl
		//make the new StaticInitDecl/clinit if it's necessary, do that between the first and second loop

		boolean newStaticInitNecessary = false;

		if(cd.body() != null) {
			for (int i = 0; i < cd.body().nchildren; i++) {
				if (cd.body().children[i] instanceof FieldDecl) {
					cd.body().children[i].visit(this);
					FieldDecl currFieldDecl = (FieldDecl)cd.body().children[i];
					if(cd.methodTable.get("<clinit>") == null) {
						if (currFieldDecl.getModifiers().isStatic() && (currFieldDecl.var().init() != null) && (!currFieldDecl.modifiers.isFinal() || (currFieldDecl.modifiers.isFinal() && !(currFieldDecl.var().init() instanceof Literal)))) {
							newStaticInitNecessary = true;
						}
					}
				}
			}

			//ASK
			if(newStaticInitNecessary){
				cd.body().append(new StaticInitDecl(new Block(new Sequence())));
			}

			for (int i = 0; i < cd.body().nchildren; i++) {
				if (!(cd.body().children[i] instanceof FieldDecl)) {
					cd.body().children[i].visit(this);
				}
			}
		}

		cd.classFile = gen.getClassFile();

		return null;
	}

	// CONSTRUCTOR DECLARATION
	public Object visitConstructorDecl(ConstructorDecl cd) {
		println(cd.line + ": ConstructorDecl: Generating Code for constructor for class " + cd.name().getname());

		classFile.startMethod(cd);
		classFile.addComment(cd, "Constructor Declaration");

		// 12/05/13 = removed if (just in case this ever breaks ;-) )
		cd.cinvocation().visit(this);

		// YOUR CODE HERE
		//What else here?
		if(cd.cinvocation().superConstructorCall()) {
			classFile.addComment(cd, "Field Init Generation Start");
			currentClass.visit(new GenerateFieldInits(gen, currentClass, false));
			classFile.addComment(cd, "Field Init Generation End");
		}
		if(cd.body() != null){
			cd.body().visit(this);
		}

		classFile.addInstruction(new Instruction(RuntimeConstants.opc_return));

		// We are done generating code for this method, so transfer it to the classDecl.
		cd.setCode(classFile.getCurrentMethodCode());
		classFile.endMethod();

		return null;
	}


	// CONTINUE STATEMENT
	public Object visitContinueStat(ContinueStat cs) {
		println(cs.line + ": ContinueStat:\tGenerating code.");
		classFile.addComment(cs, "Continue Statement");

		// YOUR CODE HERE
		String currContinueLabel = gen.getContinueLabel();
		classFile.addInstruction(new JumpInstruction(RuntimeConstants.opc_goto, currContinueLabel));

		classFile.addComment(cs, "End ContinueStat");
		return null;
	}

	// DO STATEMENT
	public Object visitDoStat(DoStat ds) {
		println(ds.line + ": DoStat:\tGenerating code.");
		classFile.addComment(ds, "Do Statement");

		// YOUR CODE HERE

		//REFERENCE GENERATES AN EXTRA LABEL, I THINK

		String topLabel = "L" + gen.getLabel();
		String endLabel = "L" + gen.getLabel();

		String extraLabel = "L" + gen.getLabel();

		gen.setContinueLabel(topLabel);
		gen.setBreakLabel(endLabel);

		classFile.addInstruction(new LabelInstruction(RuntimeConstants.opc_label, topLabel));

		classFile.addInstruction(new LabelInstruction(RuntimeConstants.opc_label, extraLabel));


		if (ds.stat() != null){
			ds.stat().visit(this);
		}

		ds.expr().visit(this);

		classFile.addInstruction(new JumpInstruction(RuntimeConstants.opc_ifeq, endLabel));
		classFile.addInstruction(new JumpInstruction(RuntimeConstants.opc_goto, topLabel));
		classFile.addInstruction(new LabelInstruction(RuntimeConstants.opc_label, endLabel));



		classFile.addComment(ds, "End DoStat");
		return null; 
	}


	// EXPRESSION STATEMENT
	public Object visitExprStat(ExprStat es) {	
		println(es.line + ": ExprStat:\tVisiting an Expression Statement.");
		classFile.addComment(es, "Expression Statement");

		es.expression().visit(this);
		if (es.expression() instanceof Invocation) {
			Invocation in = (Invocation)es.expression();

			if (in.targetType.isStringType() && in.methodName().getname().equals("length")) {
			    println(es.line + ": ExprStat:\tInvocation of method length, return value not uses.");
			    gen.dup(es.expression().type, RuntimeConstants.opc_pop, RuntimeConstants.opc_pop2);
			} else if (in.targetType.isStringType() && in.methodName().getname().equals("charAt")) {
			    println(es.line + ": ExprStat:\tInvocation of method charAt, return value not uses.");
			    gen.dup(es.expression().type, RuntimeConstants.opc_pop, RuntimeConstants.opc_pop2);
			} else if (in.targetMethod.returnType().isVoidType())
				println(es.line + ": ExprStat:\tInvocation of Void method where return value is not used anyways (no POP needed)."); 
			else {
				println(es.line + ": ExprStat:\tPOP added to remove non used return value for a '" + es.expression().getClass().getName() + "'.");
				gen.dup(es.expression().type, RuntimeConstants.opc_pop, RuntimeConstants.opc_pop2);
			}
		}
		else 
			if (!(es.expression() instanceof Assignment)) {
				gen.dup(es.expression().type, RuntimeConstants.opc_pop, RuntimeConstants.opc_pop2);
				println(es.line + ": ExprStat:\tPOP added to remove unused value left on stack for a '" + es.expression().getClass().getName() + "'.");
			}
		classFile.addComment(es, "End ExprStat");
		return null;
	}

	// FIELD DECLARATION
	public Object visitFieldDecl(FieldDecl fd) {
		println(fd.line + ": FieldDecl:\tGenerating code.");

		classFile.addField(fd);

		return null;
	}

	// FIELD REFERENCE
	public Object visitFieldRef(FieldRef fr) {
		println(fr.line + ": FieldRef:\tGenerating code (getfield code only!).");

		// Changed June 22 2012 Array
		// If we have and field reference with the name 'length' and an array target type
		if (fr.myDecl == null) { // We had a array.length reference. Not the nicest way to check!!
			classFile.addComment(fr, "Array length");
			fr.target().visit(this);
			classFile.addInstruction(new Instruction(RuntimeConstants.opc_arraylength));
			return null;
		}

		classFile.addComment(fr,  "Field Reference");

		// Note when visiting this node we assume that the field reference
		// is not a left hand side, i.e. we always generate 'getfield' code.

		// Generate code for the target. This leaves a reference on the 
		// stack. pop if the field is static!
		fr.target().visit(this);
		if (!fr.myDecl.modifiers.isStatic()) 
			classFile.addInstruction(new FieldRefInstruction(RuntimeConstants.opc_getfield, 
					fr.targetType.typeName(), fr.fieldName().getname(), fr.type.signature()));
		else {
			// If the target is that name of a class and the field is static, then we don't need a pop; else we do:
			if (!(fr.target() instanceof NameExpr && (((NameExpr)fr.target()).myDecl instanceof ClassDecl))) 
				classFile.addInstruction(new Instruction(RuntimeConstants.opc_pop));
			classFile.addInstruction(new FieldRefInstruction(RuntimeConstants.opc_getstatic,
					fr.targetType.typeName(), fr.fieldName().getname(),  fr.type.signature()));
		}
		classFile.addComment(fr, "End FieldRef");
		return null;
	}


	// FOR STATEMENT
	public Object visitForStat(ForStat fs) {
		println(fs.line + ": ForStat:\tGenerating code.");
		classFile.addComment(fs, "For Statement");
		// YOUR CODE HERE

		String oldBreak = gen.getBreakLabel();
		String oldContinue = gen.getContinueLabel();

		String topLabel = "L" + gen.getLabel();
		String endLabel = "L" + gen.getLabel();
		String incrementLabel = "L" + gen.getLabel();

		//CONTINUE LABEL IS *NOT* END LABEL, JUMP TO LABEL FOR INCREMENT

		gen.setContinueLabel(incrementLabel);
		gen.setBreakLabel(endLabel);

		if (fs.init() != null){
			fs.init().visit(this);
		}
		classFile.addInstruction(new LabelInstruction(RuntimeConstants.opc_label, topLabel));

		if (fs.expr() != null) {
			fs.expr().visit(this);
			classFile.addInstruction(new JumpInstruction(RuntimeConstants.opc_ifeq, endLabel));
		}
		if(fs.stats() != null){
			fs.stats().visit(this);
		}

		classFile.addInstruction(new LabelInstruction(RuntimeConstants.opc_label, incrementLabel));

		if(fs.incr() != null){
			fs.incr().visit(this);
		}

		classFile.addInstruction(new JumpInstruction(RuntimeConstants.opc_goto, topLabel));
		classFile.addInstruction(new LabelInstruction(RuntimeConstants.opc_label, endLabel));

		gen.setContinueLabel(oldContinue);
		gen.setBreakLabel(oldBreak);

		classFile.addComment(fs, "End ForStat");	
		return null;
	}

	// IF STATEMENT
	public Object visitIfStat(IfStat is) {
		println(is.line + ": IfStat:\tGenerating code.");
		classFile.addComment(is, "If Statement");

		// YOUR CODE HERE



		//is.expr().visit(this);
		if (is.elsepart() == null){
			String endLabel = "L" + gen.getLabel();

			is.expr().visit(this);


			classFile.addInstruction(new JumpInstruction(RuntimeConstants.opc_ifeq, endLabel));
			if(is.thenpart() != null){
				is.thenpart().visit(this);
			}
			classFile.addInstruction(new LabelInstruction(RuntimeConstants.opc_label, endLabel));
		}
		else{
			String elseLabel = "L" + gen.getLabel();
			String endLabel = "L" + gen.getLabel();

			is.expr().visit(this);


			classFile.addInstruction(new JumpInstruction(RuntimeConstants.opc_ifeq, elseLabel));
			if(is.thenpart() != null){
				is.thenpart().visit(this);
			}
			classFile.addInstruction(new JumpInstruction(RuntimeConstants.opc_goto, endLabel));
			classFile.addInstruction(new LabelInstruction(RuntimeConstants.opc_label, elseLabel));
			is.elsepart().visit(this);
			classFile.addInstruction(new LabelInstruction(RuntimeConstants.opc_label, endLabel));
		}

		classFile.addComment(is,  "End IfStat");
		return null;
	}


	// INVOCATION
	public Object visitInvocation(Invocation in) {
	    println(in.line + ": Invocation:\tGenerating code for invoking method '" + in.methodName().getname() + "' in class '" + in.targetType.typeName() + "'.");
		classFile.addComment(in, "Invocation");
		// YOUR CODE HERE

		//visit target
		//remove target if it's a static method and target is NOT a class name (pop it off)
		//visit parameters
		//convert parameters
		//invokevirtual/special/etc. depending on the condition


		if (in.target() != null){
			in.target().visit(this);
		}
		else if((in.target() == null) && !in.targetMethod.getModifiers().isStatic()){
			classFile.addInstruction(new Instruction(RuntimeConstants.opc_aload_0));
		}

		if(in.target() != null) {
			if ((in.targetMethod.getModifiers().isStatic()) && !(in.target() instanceof NameExpr && (((NameExpr) in.target()).myDecl instanceof ClassDecl)))
				classFile.addInstruction(new Instruction(RuntimeConstants.opc_pop));
		}

		//Here to check something
		//System.out.println(in.targetMethod.getname());
		//if(in.targetMethod.getname().equals("println")){
		//	classFile.addInstruction(new Instruction(RuntimeConstants.opc_pop));
		//}

		for(int i = 0; i < in.params().nchildren; i++) {
			in.params().children[i].visit(this);
			if (in.params().children[i] instanceof NameExpr) {
				NameExpr currArg = (NameExpr) in.params().children[i];
				if (currArg.myDecl instanceof VarDecl) {
					gen.dataConvert(((VarDecl) currArg.myDecl).type(), ((ParamDecl) in.targetMethod.params().children[i]).type());
				}
			}
			else if(in.params().children[i] instanceof Literal){
				gen.dataConvert(new PrimitiveType(((Literal) in.params().children[i]).getKind()), ((ParamDecl) in.targetMethod.params().children[i]).type());
			}
		}

		if (in.targetMethod.getModifiers().isStatic()){
			classFile.addInstruction(new MethodInvocationInstruction(RuntimeConstants.opc_invokestatic, in.targetMethod.getMyClass().name(), in.targetMethod.getname(), "(" + in.targetMethod.paramSignature() + ")" + in.targetMethod.returnType().signature()));
		}
		else if(in.targetMethod.getModifiers().isPrivate() || (in.target() instanceof Super)){
			classFile.addInstruction(new MethodInvocationInstruction(RuntimeConstants.opc_invokespecial, in.targetMethod.getMyClass().name(), in.targetMethod.getname(), "(" + in.targetMethod.paramSignature() + ")" + in.targetMethod.returnType().signature()));
		}
		else if(!in.targetMethod.getModifiers().isStatic() && in.targetMethod.isInterfaceMember()){
			//loop through parameters, add 1 for everything except longs and doubles (for those add 2), then add 1 at end

			classFile.addInstruction(new MethodInvocationInstruction(RuntimeConstants.opc_invokeinterface, in.targetMethod.getMyClass().name(), in.targetMethod.getname(), "(" + in.targetMethod.paramSignature() + ")" + in.targetMethod.returnType().signature() ));
		}
		else if(!in.targetMethod.getModifiers().isStatic() && !in.targetMethod.isInterfaceMember()){
			classFile.addInstruction(new MethodInvocationInstruction(RuntimeConstants.opc_invokevirtual, in.targetMethod.getMyClass().name(), in.targetMethod.getname(), "(" + in.targetMethod.paramSignature() + ")" + in.targetMethod.returnType().signature()));
		}

		classFile.addComment(in, "End Invocation");

		return null;
	}

	// LITERAL
	public Object visitLiteral(Literal li) {
		println(li.line + ": Literal:\tGenerating code for Literal '" + li.getText() + "'.");
		classFile.addComment(li, "Literal");

		switch (li.getKind()) {
		case Literal.ByteKind:
		case Literal.CharKind:
		case Literal.ShortKind:
		case Literal.IntKind:
			gen.loadInt(li.getText());
			break;
		case Literal.NullKind:
			classFile.addInstruction(new Instruction(RuntimeConstants.opc_aconst_null));
			break;
		case Literal.BooleanKind:
			if (li.getText().equals("true")) 
				classFile.addInstruction(new Instruction(RuntimeConstants.opc_iconst_1));
			else
				classFile.addInstruction(new Instruction(RuntimeConstants.opc_iconst_0));
			break;
		case Literal.FloatKind:
			gen.loadFloat(li.getText());
			break;
		case Literal.DoubleKind:
			gen.loadDouble(li.getText());
			break;
		case Literal.StringKind:
			gen.loadString(li.getText());
			break;
		case Literal.LongKind:
			gen.loadLong(li.getText());
			break;	    
		}
		classFile.addComment(li,  "End Literal");
		return null;
	}

	// LOCAL VARIABLE DECLARATION
	public Object visitLocalDecl(LocalDecl ld) {
		if (ld.var().init() != null) {
			println(ld.line + ": LocalDecl:\tGenerating code for the initializer for variable '" + 
					ld.var().name().getname() + "'.");
			classFile.addComment(ld, "Local Variable Declaration");

			// YOUR CODE HERE
			//ASK
			if (ld.var().init() != null){
				boolean oldRHS = RHSofAssignment;

				RHSofAssignment = true;
				ld.var().init().visit(this);
				RHSofAssignment = oldRHS;

				gen.dataConvert(ld.var().init().type, ld.type());

				if (ld.var().myDecl.address() < 4)
					classFile.addInstruction(new Instruction(Generator.getStoreInstruction(ld.var().myDecl.type(), ld.var().myDecl.address(), false)));
				else {
					classFile.addInstruction(new SimpleInstruction(Generator.getStoreInstruction(ld.var().myDecl.type(), ld.var().myDecl.address(), false), ld.var().myDecl.address()));
				}
			}

			classFile.addComment(ld, "End LocalDecl");
		}
		else
			println(ld.line + ": LocalDecl:\tVisiting local variable declaration for variable '" + ld.var().name().getname() + "'.");

		return null;
	}

	// METHOD DECLARATION
	public Object visitMethodDecl(MethodDecl md) {
		println(md.line + ": MethodDecl:\tGenerating code for method '" + md.name().getname() + "'.");	
		classFile.startMethod(md);

		classFile.addComment(md, "Method Declaration (" + md.name() + ")");

		if (md.block() !=null) 
			md.block().visit(this);
		gen.endMethod(md);
		return null;
	}


	// NAME EXPRESSION
	public Object visitNameExpr(NameExpr ne) {
		classFile.addComment(ne, "Name Expression --");

		// ADDED 22 June 2012 
		if (ne.myDecl instanceof ClassDecl) {
			println(ne.line + ": NameExpr:\tWas a class name - skip it :" + ne.name().getname());
			classFile.addComment(ne, "End NameExpr");
			return null;
		}

		// YOUR CODE HERE

		VarDecl neVar = (VarDecl)ne.myDecl;
		if (neVar.address() >= 4){
			classFile.addInstruction(new SimpleInstruction(gen.getLoadInstruction(ne.type, neVar.address(), false), neVar.address()));
		}
		else{
			classFile.addInstruction(new Instruction(gen.getLoadInstruction(ne.type, neVar.address(), false)));
		}

		classFile.addComment(ne, "End NameExpr");
		return null;
	}

	// NEW
	public Object visitNew(New ne) {
		println(ne.line + ": New:\tGenerating code");
		classFile.addComment(ne, "New");

		// YOUR CODE HERE

		//new A, then duplicate it
		//target is the new object
		classFile.addInstruction(new ClassRefInstruction(RuntimeConstants.opc_new, ne.getConstructorDecl().getname()));
		classFile.addInstruction(new Instruction(RuntimeConstants.opc_dup));

		for(int i = 0; i < ne.args().nchildren; i++){
			ne.args().children[i].visit(this);
			if(ne.args().children[i] instanceof NameExpr) {
				NameExpr currArg = (NameExpr) ne.args().children[i];
				if (currArg.myDecl instanceof VarDecl) {
					gen.dataConvert(((VarDecl) currArg.myDecl).type(), ((ParamDecl) ne.getConstructorDecl().params().children[i]).type());
				}
			}
			else if(ne.args().children[i] instanceof Literal){
				gen.dataConvert(new PrimitiveType(((Literal) ne.args().children[i]).getKind()), ((ParamDecl) ne.getConstructorDecl().params().children[i]).type());
			}
		}

		//Ask method name and param signature
		//classFile.addInstruction(new MethodInvocationInstruction(RuntimeConstants.opc_invokespecial, ne.type().myDecl.name(), ne.getConstructorDecl().getname(), ne.getConstructorDecl().paramSignature()));
		classFile.addInstruction(new MethodInvocationInstruction(RuntimeConstants.opc_invokespecial, ne.type().myDecl.name(), "<init>", "(" + ne.getConstructorDecl().paramSignature() + ")" + "V"));


		classFile.addComment(ne, "End New");
		return null;
	}

	// RETURN STATEMENT
	public Object visitReturnStat(ReturnStat rs) {
		println(rs.line + ": ReturnStat:\tGenerating code.");
		classFile.addComment(rs, "Return Statement");

		// YOUR CODE HERE
		if(rs.expr() != null){
			rs.expr().visit(this);
		}

		//Before returning for numerics, have to cast to returnType of the currentMethod
		//Fix

		if (rs.expr() != null) {
			if (rs.expr().type.isNumericType()) {
				if (((PrimitiveType) rs.expr().type).getKind() != ((PrimitiveType) rs.getType()).getKind()) {
					gen.dataConvert(rs.expr().type, rs.getType());
				}
			}
		}

		if(rs.getType() != null) {
			if (rs.getType().isVoidType()) {
				classFile.addInstruction(new Instruction(RuntimeConstants.opc_return));
			} else {
				classFile.addInstruction(new Instruction(gen.getOpCodeFromString(rs.getType().getTypePrefix() + "return")));
			}
		}
		else{
			classFile.addInstruction(new Instruction(RuntimeConstants.opc_return));
		}

		classFile.addComment(rs, "End ReturnStat");
		return null;
	}

	// STATIC INITIALIZER
	public Object visitStaticInitDecl(StaticInitDecl si) {
		println(si.line + ": StaticInit:\tGenerating code for a Static initializer.");	

		classFile.startMethod(si);
		classFile.addComment(si, "Static Initializer");

		// YOUR CODE HERE
		//Anything else here?
		//Set initializedFields?
		classFile.addComment(si, "Field Init Generation Start");
		currentClass.visit(new GenerateFieldInits(gen, currentClass, true));
		classFile.addComment(si, "Field Init Generation End");

		si.initializer().visit(this);

		classFile.addInstruction(new Instruction(RuntimeConstants.opc_return));

		si.setCode(classFile.getCurrentMethodCode());
		classFile.endMethod();
		return null;
	}

	// SUPER
	public Object visitSuper(Super su) {
		println(su.line + ": Super:\tGenerating code (access).");	
		classFile.addComment(su, "Super");

		// YOUR CODE HERE
		classFile.addInstruction(new Instruction(RuntimeConstants.opc_aload_0));

		classFile.addComment(su, "End Super");
		return null;
	}

	// SWITCH STATEMENT
	public Object visitSwitchStat(SwitchStat ss) {
		println(ss.line + ": Switch Statement:\tGenerating code for Switch Statement.");
		int def = -1;
		SortedMap<Object, SwitchLabel> sm = new TreeMap<Object, SwitchLabel>();
		classFile.addComment(ss,  "Switch Statement");

		SwitchGroup sg = null;
		SwitchLabel sl = null;

		// just to make sure we can do breaks;
		boolean oldinsideSwitch = insideSwitch;
		insideSwitch = true;
		String oldBreakLabel = Generator.getBreakLabel();
		Generator.setBreakLabel("L"+gen.getLabel());

		// Generate code for the item to switch on.
		ss.expr().visit(this);	
		// Write the lookup table
		for (int i=0;i<ss.switchBlocks().nchildren; i++) {
			sg = (SwitchGroup)ss.switchBlocks().children[i];
			sg.setLabel(gen.getLabel());
			for(int j=0; j<sg.labels().nchildren;j++) {
				sl = (SwitchLabel)sg.labels().children[j];
				sl.setSwitchGroup(sg);
				if (sl.isDefault())
					def = i;
				else
					sm.put(sl.expr().constantValue(), sl);
			}
		}

		for (Iterator<Object> ii=sm.keySet().iterator(); ii.hasNext();) {
			sl = sm.get(ii.next());
		}

		// default comes last, if its not there generate an empty one.
		if (def != -1) {
			classFile.addInstruction(new LookupSwitchInstruction(RuntimeConstants.opc_lookupswitch, sm, 
					"L" + ((SwitchGroup)ss.switchBlocks().children[def]).getLabel()));
		} else {
			// if no default label was there then just jump to the break label.
			classFile.addInstruction(new LookupSwitchInstruction(RuntimeConstants.opc_lookupswitch, sm, 
					Generator.getBreakLabel()));
		}

		// Now write the code and the labels.
		for (int i=0;i<ss.switchBlocks().nchildren; i++) {
			sg = (SwitchGroup)ss.switchBlocks().children[i];
			classFile.addInstruction(new LabelInstruction(RuntimeConstants.opc_label, "L"+sg.getLabel()));
			sg.statements().visit(this);
		}

		// Put the break label in;
		classFile.addInstruction(new LabelInstruction(RuntimeConstants.opc_label, Generator.getBreakLabel()));
		insideSwitch = oldinsideSwitch;
		Generator.setBreakLabel(oldBreakLabel);
		classFile.addComment(ss, "End SwitchStat");
		return null;
	}

	// TERNARY EXPRESSION 
	public Object visitTernary(Ternary te) {
		println(te.line + ": Ternary:\tGenerating code.");
		classFile.addComment(te, "Ternary Statement");

		boolean OldStringBuilderCreated = StringBuilderCreated;
		StringBuilderCreated = false;

		// YOUR CODE HERE
		classFile.addComment(te, "Ternary");
		StringBuilderCreated = OldStringBuilderCreated;
		return null;
	}

	// THIS
	public Object visitThis(This th) {
		println(th.line + ": This:\tGenerating code (access).");       
		classFile.addComment(th, "This");

		// YOUR CODE HERE
		classFile.addInstruction(new Instruction(RuntimeConstants.opc_aload_0));

		classFile.addComment(th, "End This");
		return null;
	}

	// UNARY POST EXPRESSION
	public Object visitUnaryPostExpr(UnaryPostExpr up) {
		println(up.line + ": UnaryPostExpr:\tGenerating code.");
		classFile.addComment(up, "Unary Post Expression");

		// YOUR CODE HERE


		String operation = null;
		int increment = 0;

		switch(up.op().getKind()){
			case PostOp.PLUSPLUS:{
				operation = "add";
				increment = 1;
				break;
			}
			case PostOp.MINUSMINUS:{
				operation = "sub";
				increment = -1;
				break;
			}
		}

		if (up.expr() instanceof NameExpr) {

			up.expr().visit(this);

			VarDecl upVar = (VarDecl) ((NameExpr) up.expr()).myDecl;

			if (upVar.type().isIntegerType()) {
				classFile.addInstruction(new IincInstruction(RuntimeConstants.opc_iinc, upVar.address(), increment));
			} else {
				gen.dup(up.expr().type, RuntimeConstants.opc_dup, RuntimeConstants.opc_dup2);
				classFile.addInstruction(new Instruction(gen.loadConstant1(upVar.type())));
				classFile.addInstruction(new Instruction(gen.getOpCodeFromString(up.expr().type.getTypePrefix() + operation)));
				if (upVar.address() < 4){
					classFile.addInstruction(new Instruction(Generator.getStoreInstruction(upVar.type(), upVar.address(), false)));
				}
				else {
					classFile.addInstruction(new SimpleInstruction(Generator.getStoreInstruction(upVar.type(), upVar.address(), false), upVar.address()));
				}
			}
		}
		else if (up.expr() instanceof FieldRef){
			FieldRef upFieldRef = (FieldRef) up.expr();

			upFieldRef.target().visit(this);


			if(!upFieldRef.myDecl.getModifiers().isStatic()){
				gen.dup(up.expr().type, RuntimeConstants.opc_dup, RuntimeConstants.opc_dup2);
				classFile.addInstruction(new FieldRefInstruction(RuntimeConstants.opc_getfield, upFieldRef.targetType.typeName(),
						upFieldRef.fieldName().getname(), upFieldRef.type.signature()));
				FieldDecl upVar = ((FieldRef) up.expr()).myDecl;
				gen.dup(up.expr().type, RuntimeConstants.opc_dup_x1, RuntimeConstants.opc_dup_x2);
				classFile.addInstruction(new Instruction(gen.loadConstant1(upVar.type())));
				classFile.addInstruction(new Instruction(gen.getOpCodeFromString(up.expr().type.getTypePrefix() + operation)));
				classFile.addInstruction(new FieldRefInstruction(RuntimeConstants.opc_putfield, upFieldRef.targetType.typeName(),
						upFieldRef.fieldName().getname(), upFieldRef.type.signature()));
			}
			else{
				if(upFieldRef.target() != null) {
					if (!(upFieldRef.target() instanceof NameExpr && (((NameExpr) upFieldRef.target()).myDecl instanceof ClassDecl))) {
						classFile.addInstruction(new Instruction(RuntimeConstants.opc_pop));
					}
				}

				classFile.addInstruction(new FieldRefInstruction(RuntimeConstants.opc_getstatic, upFieldRef.targetType.typeName(),
						upFieldRef.fieldName().getname(), upFieldRef.type.signature()));

				FieldDecl upVar = ((FieldRef) up.expr()).myDecl;
				gen.dup(up.expr().type, RuntimeConstants.opc_dup, RuntimeConstants.opc_dup2);
				classFile.addInstruction(new Instruction(gen.loadConstant1(upVar.type())));
				classFile.addInstruction(new Instruction(gen.getOpCodeFromString(up.expr().type.getTypePrefix() + operation)));
				classFile.addInstruction(new FieldRefInstruction(RuntimeConstants.opc_putstatic, upFieldRef.targetType.typeName(),
						upFieldRef.fieldName().getname(), upFieldRef.type.signature()));
			}
		}

		classFile.addComment(up, "End UnaryPostExpr");
		return null;
	}

	// UNARY PRE EXPRESSION
	public Object visitUnaryPreExpr(UnaryPreExpr up) {
		println(up.line + ": UnaryPreExpr:\tGenerating code for " + up.op().operator() + " : " + up.expr().type.typeName() + " -> " + up.expr().type.typeName() + ".");
		classFile.addComment(up,"Unary Pre Expression");

		// YOUR CODE HERE

		//FOR BOTH ++ and -- NEED TO HAVE DIFFERENT CASES FOR NAMEXPR AND FIELDREF
		//can't use iinc on a field

		//non-static field: visit target
		//static field: visit target, target is ClassName have to then pop, getStatic instruction

		String operation = null;
		int increment = 0;

		switch(up.op().getKind()){
			case PostOp.PLUSPLUS:{
				operation = "add";
				increment = 1;
				break;
			}
			case PostOp.MINUSMINUS:{
				operation = "sub";
				increment = -1;
				break;
			}
		}

		if((up.op().getKind() == PreOp.PLUSPLUS) || (up.op().getKind() == PreOp.MINUSMINUS)){
			if (up.expr() instanceof NameExpr) {
				VarDecl upVar = (VarDecl) ((NameExpr) up.expr()).myDecl;

				if (upVar.type().isIntegerType()) {
					classFile.addInstruction(new IincInstruction(RuntimeConstants.opc_iinc, upVar.address(), increment));
					up.expr().visit(this);
				} else {
					up.expr().visit(this);
					classFile.addInstruction(new SimpleInstruction(Generator.getLoadInstruction(upVar.type(), upVar.address(), false), upVar.address()));
					classFile.addInstruction(new Instruction(gen.loadConstant1(upVar.type())));
					classFile.addInstruction(new Instruction(gen.getOpCodeFromString(up.expr().type.getTypePrefix() + operation)));
					gen.dup(up.expr().type, RuntimeConstants.opc_dup, RuntimeConstants.opc_dup);
					if (upVar.address() < 4){
						classFile.addInstruction(new Instruction(Generator.getStoreInstruction(upVar.type(), upVar.address(), false)));
					}
					else {
						classFile.addInstruction(new SimpleInstruction(Generator.getStoreInstruction(upVar.type(), upVar.address(), false), upVar.address()));
					}
				}
			}
			else if (up.expr() instanceof FieldRef){
				FieldRef upFieldRef = (FieldRef) up.expr();
				upFieldRef.target().visit(this);

				if(upFieldRef.target() != null) {
					if (!(upFieldRef.target() instanceof NameExpr && (((NameExpr) upFieldRef.target()).myDecl instanceof ClassDecl))) {
						classFile.addInstruction(new Instruction(RuntimeConstants.opc_pop));
						classFile.addInstruction(new FieldRefInstruction(RuntimeConstants.opc_getstatic, upFieldRef.targetType.typeName(),
								upFieldRef.fieldName().getname(), upFieldRef.type.signature()));
					}
				}

				if(upFieldRef.myDecl.getModifiers().isStatic()){
					FieldDecl upVar = ((FieldRef) up.expr()).myDecl;
					classFile.addInstruction(new SimpleInstruction(Generator.getLoadInstruction(upVar.type(), upVar.address(), false), upVar.address()));
					classFile.addInstruction(new Instruction(gen.loadConstant1(upVar.type())));
					classFile.addInstruction(new Instruction(gen.getOpCodeFromString(up.expr().type.getTypePrefix() + operation)));
					//Other two parameters here?
					gen.dup(up.expr().type, RuntimeConstants.opc_dup_x1, RuntimeConstants.opc_dup_x2);
					if (upVar.address() < 4){
						classFile.addInstruction(new Instruction(Generator.getStoreInstruction(upVar.type(), upVar.address(), false)));
					}
					else {
						classFile.addInstruction(new SimpleInstruction(Generator.getStoreInstruction(upVar.type(), upVar.address(), false), upVar.address()));
					}
				}
				else{
					FieldDecl upVar = ((FieldRef) up.expr()).myDecl;
					classFile.addInstruction(new SimpleInstruction(Generator.getLoadInstruction(upVar.type(), upVar.address(), false), upVar.address()));
					classFile.addInstruction(new Instruction(gen.loadConstant1(upVar.type())));
					classFile.addInstruction(new Instruction(gen.getOpCodeFromString(up.expr().type.getTypePrefix() + operation)));
					//Other two parameters here?
					gen.dup(up.expr().type, RuntimeConstants.opc_dup, RuntimeConstants.opc_dup2);
					if (upVar.address() < 4){
						classFile.addInstruction(new Instruction(Generator.getStoreInstruction(upVar.type(), upVar.address(), false)));
					}
					else {
						classFile.addInstruction(new SimpleInstruction(Generator.getStoreInstruction(upVar.type(), upVar.address(), false), upVar.address()));
					}
				}
			}
		}
		else {
			switch (up.op().getKind()) {
				case PreOp.PLUS: {
					up.expr().visit(this);
					break;
				}
				case PreOp.MINUS: {
					up.expr().visit(this);
					classFile.addInstruction(new Instruction(gen.getOpCodeFromString(up.expr().type.getTypePrefix() + "neg")));
					break;
				}
				case PreOp.COMP: {
					up.expr().visit(this);
					if (up.expr().type.isIntegerType()) {
						classFile.addInstruction(new Instruction(RuntimeConstants.opc_iconst_m1));
					} else {
						classFile.addInstruction(new LdcLongInstruction(RuntimeConstants.opc_ldc, -1) {
						});
					}
					classFile.addInstruction(new Instruction(gen.getOpCodeFromString(up.expr().type.getTypePrefix() + "xor")));
					break;
				}
				case PreOp.NOT: {
					up.expr().visit(this);
					classFile.addInstruction(new Instruction(RuntimeConstants.opc_iconst_1));
					classFile.addInstruction(new Instruction(RuntimeConstants.opc_ixor));
					break;
				}
			}
		}


		classFile.addComment(up, "End UnaryPreExpr");
		return null;
	}

	// WHILE STATEMENT
	public Object visitWhileStat(WhileStat ws) {
		println(ws.line + ": While Stat:\tGenerating Code.");

		classFile.addComment(ws, "While Statement");

		// YOUR CODE HERE
		//ASK LABEL OPCODE
		String topLabel = "L" + gen.getLabel();
		String endLabel = "L" + gen.getLabel();

		//Do this for other iterative structures
		String oldContinueLabel = gen.getContinueLabel();
		String oldBreakLabel = gen.getBreakLabel();

		gen.setContinueLabel(topLabel);
		gen.setBreakLabel(endLabel);

		classFile.addInstruction(new LabelInstruction(RuntimeConstants.opc_label, topLabel));
		ws.expr().visit(this);
		classFile.addInstruction(new JumpInstruction(RuntimeConstants.opc_ifeq, endLabel));

		if(ws.stat() != null){
			ws.stat().visit(this);
		}

		classFile.addInstruction(new JumpInstruction(RuntimeConstants.opc_goto, topLabel));
		classFile.addInstruction(new LabelInstruction(RuntimeConstants.opc_label, endLabel));

		gen.setContinueLabel(oldContinueLabel);
		gen.setBreakLabel(oldBreakLabel);

		classFile.addComment(ws, "End WhileStat");	
		return null;
	}
}

