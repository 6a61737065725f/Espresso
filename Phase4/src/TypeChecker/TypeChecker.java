package TypeChecker;

import AST.*;
import Utilities.Error;
import Utilities.SymbolTable;
import Utilities.Visitor;

import java.sql.SQLOutput;
import java.util.*;
import java.math.*;

public class TypeChecker extends Visitor {

    public static ClassBodyDecl findMethod(Sequence candidateMethods, String name, Sequence actualParams, 
					   boolean lookingForMethods) {
	
	if (lookingForMethods) {
	    println("+------------- findMethod (Method) ------------");
	    println("| Looking for method: " + name);
	} else {
	    println("+---------- findMethod (Constructor) ----------");
	    println("| Looking for constructor: " + name);
	}
	println("| With parameters:");
	for (int i=0; i<actualParams.nchildren; i++){
	    println("|   " + i + ". " + ((actualParams.children[i] instanceof ParamDecl)?(((ParamDecl)actualParams.children[i]).type()):((Expression)actualParams.children[i]).type));
	}
	// The number of actual parameters in the invocation.
	int count = 0;
	
	// Make an array big enough to hold all the methods if needed
	ClassBodyDecl cds[] = new ClassBodyDecl[candidateMethods.nchildren];
	
	// Initialize the array to point to null
	for(int i=0;i<candidateMethods.nchildren;i++) 
	    cds[i] = null;
	
	Sequence args = actualParams;
	Sequence params;
	
	// Insert all the methods from the symbol table that:
	// 1.) has the right number of parameters
	// 2.) each formal parameter can be assigned its corresponding
	//     actual parameter.
	if (lookingForMethods)
	    println("| Finding methods with the right number of parameters and types");
	else
	    println("| Finding constructors with the right number of parameters and types");
	for (int cnt=0; cnt<candidateMethods.nchildren; cnt++) {
	    ClassBodyDecl cbd = (ClassBodyDecl)candidateMethods.children[cnt];
	    
	    // if the method doesn't have the right name, move on!
	    if (!(cbd.getname().equals(name)))
		continue;
	    
	    // Fill params with the formal parameters.
	    if (cbd instanceof ConstructorDecl) 
		params = ((ConstructorDecl)cbd).params();
	    else if (cbd instanceof MethodDecl)
		params = ((MethodDecl)cbd).params();
	    else
		// we have a static initializer, don't do anything - just skip it.
		continue;
	    
	    print("|   " + name + "(");
	    if (cbd instanceof ConstructorDecl) 
		print(Type.parseSignature(((ConstructorDecl)cbd).paramSignature()));
	    else 
		print(Type.parseSignature(((MethodDecl)cbd).paramSignature()));
	    print(" )  ");
	    
	    if (args.nchildren == params.nchildren) {
		// The have the same number of parameters
		// now check that the formal parameters are
		// assignmentcompatible with respect to the 
		// types of the actual parameters.
		// OBS this assumes the type field of the actual
		// parameters has been set (in Expression.java),
		// so make sure to call visit on the parameters first.
		boolean candidate = true;
		
		for (int i=0;i<args.nchildren; i++) {
		    candidate = candidate &&
			Type.assignmentCompatible(((ParamDecl)params.children[i]).type(),
						  (args.children[i] instanceof Expression) ?
						  ((Expression)args.children[i]).type :
						  ((ParamDecl)args.children[i]).type());
		    
		    if (!candidate) {
			println(" discarded");
			break;
		    }
		}
		if (candidate) {
		    println(" kept");
		    cds[count++] = cbd;
		}
	    }
	    else {
		println(" discarded");
	    }
	    
	}
	// now count == the number of candidates, and cds is the array with them.
	// if there is only one just return it!
	println("| " + count + " candidate(s) were found:");
	for ( int i=0;i<count;i++) {
	    ClassBodyDecl cbd = cds[i];
	    print("|   " + name + "(");
	    if (cbd instanceof ConstructorDecl) 
		print(Type.parseSignature(((ConstructorDecl)cbd).paramSignature()));
	    else 
		print(Type.parseSignature(((MethodDecl)cbd).paramSignature()));
	    println(" )");
	}
	
	if (count == 0) {
	    println("| No candidates were found.");
	    println("+------------- End of findMethod --------------");
	    return null;
	}
	
	if (count == 1) {
	    println("| Only one candidate - thats the one we will call then ;-)");
	    println("+------------- End of findMethod --------------");
	    return cds[0];
	}
	println("| Oh no, more than one candidate, now we must eliminate some >:-}");
	// there were more than one candidate.
	ClassBodyDecl x,y;
	int noCandidates = count;
	
	for (int i=0; i<count; i++) {
	    // take out a candidate
	    x = cds[i];
	    
	    if (x == null)
		continue;		    
	    cds[i] = null; // this way we won't find x in the next loop;
	    
	    // compare to all other candidates y. If any of these
	    // are less specialised, i.e. all types of x are 
	    // assignment compatible with those of y, y can be removed.
	    for (int j=0; j<count; j++) {
		y = cds[j];
		if (y == null) 
		    continue;
		
		boolean candidate = true;
		
		// Grab the parameters out of x and y
		Sequence xParams, yParams;
		if (x instanceof ConstructorDecl) {
		    xParams = ((ConstructorDecl)x).params();
		    yParams = ((ConstructorDecl)y).params();
		} else {
		    xParams = ((MethodDecl)x).params();
		    yParams = ((MethodDecl)y).params();
		}
		
		// now check is y[k] <: x[k] for all k. If it does remove y.
		// i.e. check if y[k] is a superclass of x[k] for all k.
		for (int k=0; k<xParams.nchildren; k++) {
		    candidate = candidate &&
			Type.assignmentCompatible(((ParamDecl)yParams.children[k]).type(),
						  ((ParamDecl)xParams.children[k]).type());
		    
		    if (!candidate)
			break;
		}
		if (candidate) {
		    // x is more specialized than y, so throw y away.
		    print("|   " + name + "(");
		    if (y instanceof ConstructorDecl) 
			print(Type.parseSignature(((ConstructorDecl)y).paramSignature()));
		    else 
			print(Type.parseSignature(((MethodDecl)y).paramSignature()));
		    print(" ) is less specialized than " + name + "(");
		    if (x instanceof ConstructorDecl) 
			print(Type.parseSignature(((ConstructorDecl)x).paramSignature()));
		    else 
			print(Type.parseSignature(((MethodDecl)x).paramSignature()));
		    println(" ) and is thus thrown away!");
		    
		    cds[j] = null;
		    noCandidates--;
		}
	    }
	    // now put x back in to cds
	    cds[i] = x;
	}
	if (noCandidates != 1) {
	    // illegal function call
	    println("| There is more than one candidate left!");
	    println("+------------- End of findMethod --------------");
	    return null;
	}
	
	// just find it and return it.
	println("| We were left with exactly one candidate to call!");
	println("+------------- End of findMethod --------------");
	for (int i=0; i<count; i++)
	    if (cds[i] != null)
		return cds[i];
	
	return null;
    }
    
    public void listCandidates(ClassDecl cd, Sequence candidateMethods, String name) {

	for (int cnt=0; cnt<candidateMethods.nchildren; cnt++) {
	    ClassBodyDecl cbd = (ClassBodyDecl)(candidateMethods.children[cnt]);

	    if (cbd.getname().equals(name)) {
		if (cbd instanceof MethodDecl)
		    System.out.println("  " + name + "(" + Type.parseSignature(((MethodDecl)cbd).paramSignature()) + " )");
		else
		    System.out.println("  " + cd.name() + "(" + Type.parseSignature(((ConstructorDecl)cbd).paramSignature()) + " )");
	    }
	}
    }

    private SymbolTable   classTable;
    private ClassDecl     currentClass;
    private ClassBodyDecl currentContext;
    private FieldDecl currentFieldDecl; // keep track of the currentFieldDecl 
    private boolean inFieldInit;        // 
	
    public TypeChecker(SymbolTable classTable, boolean debug) { 
	this.classTable = classTable; 
	this.debug = debug;
    }

    /** ArrayAccessExpr */
    public Object visitArrayAccessExpr(ArrayAccessExpr ae) {
		println(ae.line + ": Visiting ArrayAccessExpr");
		// YOUR CODE HERE

		Type targetType = (Type)ae.target().visit(this);
		Type indexType = (Type)ae.index().visit(this);

		if (!targetType.isArrayType()){
			Error.error(ae, "ArrayAccessExpr error");
		}

		if(!indexType.isIntegralType()){
			Error.error(ae, "ArrayAccessExpr error");
		}

		if(((ArrayType)targetType).getDepth() == 1){
			ae.type = ((ArrayType)targetType).baseType();
		}
		else{
			ae.type = new ArrayType(((ArrayType)targetType).baseType(), ((ArrayType)targetType).getDepth()-1);
		}


		return ae.type;
    }

    /** ArrayType */
    public Object visitArrayType(ArrayType at) {
		println(at.line + ": Visiting an ArrayType");
		println(at.line + ": ArrayType type is " + at);
		return at;
    }

    /** NewArray */
    public Object visitNewArray(NewArray ne) {
		println(ne.line + ": Visiting a NewArray " + ne.dimsExpr().nchildren + " " + ne.dims().nchildren);
		// YOUR CODE HERE

		ne.baseType().visit(this);
		for(int i = 0; i < ne.dimsExpr().nchildren; i++){
			Type dimsExprType = (Type)(ne.dimsExpr().children[i].visit(this));
			if(!dimsExprType.isIntegralType()){
				Error.error(ne, "NewArray error");
			}
		}

		ne.dims().visit(this);

		//ASK
		if(ne.init() != null){
			for(int i = 0; i < ne.init().elements().nchildren; i++){
				Type initType = (Type)ne.init().elements().children[i].visit(this);
				if(initType != ne.baseType()){
					Error.error(ne, "NewArray error");
				}
			}
		}

		ne.type = new ArrayType(ne.baseType(), ne.dims().nchildren);

		println(ne.line + ": NewArray type is " + ne.type);
		return ne.type;
    }


    // TODO: Espresso doesn't allow 'int[][] a = new int[]{ f(), f() }} where f returns an array

    public boolean arrayAssignmentCompatible(Type t, Expression e) {
	if (t instanceof ArrayType && (e instanceof ArrayLiteral)) {
	    ArrayType at = (ArrayType)t;
	    e.type = at; //  we don't know that this is the type - but if we make it through it will be!
	    ArrayLiteral al = (ArrayLiteral)e;
	    
	    // t is an array type i.e. XXXXXX[ ]
	    // e is an array literal, i.e., { }
	    if (al.elements().nchildren == 0) // the array literal is { }
		return true;   // any array variable can hold an empty array
	    // Now check that XXXXXX can hold value of the elements of al
	    // we have to make a new type: either the base type if |dims| = 1
	    boolean b = true;
	    for (int i=0; i<al.elements().nchildren; i++) {
		if (at.getDepth() == 1) 
		    b = b && arrayAssignmentCompatible(at.baseType(), (Expression)al.elements().children[i]);
		else { 
		    ArrayType at1 = new ArrayType(at.baseType(), at.getDepth()-1);
		    b = b  && arrayAssignmentCompatible(at1, (Expression)al.elements().children[i]);
		}
	    }
	    return b;
	} else if (t instanceof ArrayType && !(e instanceof ArrayLiteral)) {
	    Type t1 = (Type)e.visit(this);
	    if (t1 instanceof ArrayType)
		if (!Type.assignmentCompatible(t,t1))
		    Error.error("Incompatible type in array assignment");
		else
		    return true;
	    Error.error(t, "Error: cannot assign non array to array type " + t.typeName());	    
	}
	else if (!(t instanceof ArrayType) && (e instanceof ArrayLiteral)) {
	    Error.error(t, "Error: cannot assign value " + ((ArrayLiteral)e).toString() + " to type " + t.typeName());
	}
	return Type.assignmentCompatible(t,(Type)e.visit(this));
    }
    
    public Object visitArrayLiteral(ArrayLiteral al) {
	// Espresso does not allow array literals without the 'new <type>' part.
	Error.error(al, "Array literal must be preceeded by a 'new <type>'");
	return null;
    }
    
    /** ASSIGNMENT */
    public Object visitAssignment(Assignment as) {
	println(as.line + ": Visiting an assignment");

	Type vType = (Type) as.left().visit(this);
	Type eType = (Type) as.right().visit(this);

	/** Note: as.left() should be of NameExpr or FieldRef class! */

	if (!vType.assignable())          
	    Error.error(as,"Left hand side of assignment not assignable.");

	switch (as.op().kind) {
		case AssignmentOp.EQ : {
				// Check if the right hand side is a constant.
				// if we don't do this the following is illegal: byte b; b = 4; because 4 is an it!
			if (as.right().isConstant()) {
				if (vType.isShortType() && Literal.isShortValue(((BigDecimal)as.right().constantValue()).longValue()))
						break;
				if (vType.isByteType() && Literal.isByteValue(((BigDecimal)as.right().constantValue()).longValue()))
						break;
				if (vType.isCharType() && Literal.isCharValue(((BigDecimal)as.right().constantValue()).longValue()))
						break;
			}
			if (!Type.assignmentCompatible(vType,eType))
				Error.error(as,"Cannot assign value of type " + eType.typeName() + " to variable of type " + vType.typeName() + ".");
			break;
		}
		// YOUR CODE HERE
		// Check if setting type of assignment statement is assigned correctly

		case AssignmentOp.MULTEQ :
		case AssignmentOp.DIVEQ :
		case AssignmentOp.MODEQ :
		case AssignmentOp.MINUSEQ : {
			if (vType.isNumericType() && eType.isNumericType()) {

				if (!Type.assignmentCompatible(vType,eType))
					Error.error(as,"Cannot assign value of type " + eType.typeName() + " to variable of type " + vType.typeName() + ".");

				break;
			} else {
				Error.error(as,"Cannot assign value of type " + eType.typeName() + " to variable of type " + vType.typeName() + ".");
			}
		}

		case AssignmentOp.PLUSEQ : {
			if (vType.isNumericType() && eType.isNumericType()) {

				if (!Type.assignmentCompatible(vType,eType))
					Error.error(as,"Cannot assign value of type " + eType.typeName() + " to variable of type " + vType.typeName() + ".");

				break;
			} else if (vType.isStringType() && eType.isPrimitiveType()) {
				PrimitiveType rhs = PrimitiveType.ceilingType((PrimitiveType)vType, (PrimitiveType)eType);
				if (Type.assignmentCompatible(vType, rhs)) {
					break;
				} else {
					Error.error(as,"Cannot assign value of type " + eType.typeName() + " to variable of type " + vType.typeName() + ".");
				}
			} else {
				Error.error(as,"Cannot assign value of type " + eType.typeName() + " to variable of type " + vType.typeName() + ".");
			}
		}

		case AssignmentOp.LSHIFTEQ :
		case AssignmentOp.RSHIFTEQ :
		case AssignmentOp.RRSHIFTEQ : {

			if(!vType.isIntegralType()){
				Error.error(as,"Left hand side operand of operator \'" + as.op().operator() + "\' must be of integral type.");
			}
			if(!eType.isIntegralType()){
				Error.error(as,"Right hand side operand of operator \'" + as.op().operator() + "\' must be of integer type.");
			}
			break;
		}

		case AssignmentOp.ANDEQ :
		case AssignmentOp.OREQ :
		case AssignmentOp.XOREQ : {
			if (vType.isIntegralType() && eType.isIntegralType()) {

				//if (!Type.assignmentCompatible(vType,eType))
				//	Error.error(as,"Cannot assign value of type " + eType.typeName() + " to variable of type " + vType.typeName() + ".");

				break;
			} else if (vType.isBooleanType() && eType.isBooleanType()) {
				break;
			} else {
				Error.error(as, "Both right and left hand side operands of operator \'" + as.op().operator() + "\' must be either of boolean or similar integral type.");
			}
		}
		default: {
			Error.error(as,"Cannot assign value of type " + eType.typeName() + " to variable of type " + vType.typeName() + ".");
		}

	}
	as.type = vType;
	println(as.line + ": Assignment has type: " + as.type);

	return vType;
    }

    /** BINARY EXPRESSION */
	public Object visitBinaryExpr(BinaryExpr be) {
		println(be.line + ": Visiting a Binary Expression");

		// YOUR CODE HERE
		// make sure to check for correct error messages
		Type lType = (Type)be.left().visit(this);
		Type rType = (Type)be.right().visit(this);

		switch (be.op().kind) {
			case BinOp.LT :
			case BinOp.GT :
			case BinOp.LTEQ :
			case BinOp.GTEQ : {
				if (lType.isNumericType() && rType.isNumericType()) {
					be.type = new PrimitiveType(PrimitiveType.BooleanKind);
					break; 
				} else {
					Error.error(be,"Operator \'" + be.op().operator() + "\' requires operands of numeric type.");
				}
			}

			case BinOp.EQEQ :
			case BinOp.NOTEQ : {
				//if be.left() instanceof NameExpr and its myDecl is a classDecl, then error
				//same for right

				if (be.left() instanceof NameExpr){
					if(((NameExpr)be.left()).myDecl instanceof ClassDecl){
						Error.error(be,"Class name \'" + ((ClassDecl) ((NameExpr)be.left()).myDecl).name() + "\' cannot appear as parameter to operator \'" + be.op().operator() + "\'.");
					}
				}
				if (be.right() instanceof NameExpr){
					if(((NameExpr)be.right()).myDecl instanceof ClassDecl){
						Error.error(be,"Class name \'" + ((ClassDecl)((NameExpr)be.right()).myDecl).name() + "\' cannot appear as parameter to operator \'" + be.op().operator() + "\'.");
					}
				}


				if (lType.identical(rType)) {
					if(lType.isVoidType()){
						Error.error(be, "Void type cannot be used here.");
					}
					be.type = new PrimitiveType(PrimitiveType.BooleanKind);
					break;
				} else if (lType.isNumericType() && rType.isNumericType()) {
					be.type = new PrimitiveType(PrimitiveType.BooleanKind);
					break;
				} else {
					Error.error(be,"Operator \'" + be.op().operator() + "\' requires operands of the same type.");
				}
			}

			case BinOp.ANDAND :
			case BinOp.OROR : {
				if (lType.isBooleanType() && rType.isBooleanType()) {
					be.type = new PrimitiveType(PrimitiveType.BooleanKind);
					break;
				} else {
					Error.error(be,"Operator \'" + be.op().operator() + "\' requires operands of boolean type.");
				}
			}

			case BinOp.AND :
			case BinOp.OR :
			case BinOp.XOR : {
				if (lType.isBooleanType() && rType.isBooleanType()) {
					be.type = new PrimitiveType(PrimitiveType.BooleanKind);
					break;
				} else if (lType.isIntegralType() && rType.isIntegralType()) {
					be.type = PrimitiveType.ceilingType((PrimitiveType)lType, (PrimitiveType)rType);
					break;
				} else {
					Error.error(be,"Operator \'" + be.op().operator() + "\' requires both operands of either integral or boolean type.");
				}
			}

			case BinOp.MINUS :
			case BinOp.MULT :
			case BinOp.DIV :
			case BinOp.MOD : {
				if (lType.isNumericType() && rType.isNumericType()) {
					be.type = PrimitiveType.ceilingType((PrimitiveType)lType, (PrimitiveType)rType);
					break;
				} else {
					Error.error(be,"Operator \'" + be.op().operator() + "\' requires operands of numeric type.");
				}
			}

			case BinOp.PLUS : {
				if (lType.isNumericType() && rType.isNumericType()) {
					be.type = PrimitiveType.ceilingType((PrimitiveType)lType, (PrimitiveType)rType);
					break;
				} else if (lType.isStringType() || rType.isStringType()) {
					be.type = new PrimitiveType(PrimitiveType.StringKind);
					break;
				} else {
					Error.error(be,"Operator '+' requires operands of numeric type.");
				}
			}

			case BinOp.LSHIFT :
			case BinOp.RSHIFT :
			case BinOp.RRSHIFT : {
				if (lType.isIntegralType() && rType.isIntegralType()) {
					// ask about changing lType to integer
					if (lType.isByteType() || lType.isCharType() || lType.isShortType()) {
						be.type = new PrimitiveType(PrimitiveType.IntKind);
						break;
					} else {
						be.type = lType;
						break;
					} 
				} else if (!lType.isIntegralType()){
					Error.error(be,"Operator \'" + be.op().operator() + "\' requires left operand of integral type.");
				}
				else if (!rType.isIntegralType()){
					Error.error(be,"Operator \'" + be.op().operator() + "\' requires right operand of integral type.");
				}
			}

			// ask about how to check if class name
			case BinOp.INSTANCEOF : {
				if (be.right() instanceof NameExpr) {

					//Also check if be.left() is a NamExpr, if it is a NameExpr cannot have
					//ClassDecl for its myDecl
					if (be.left() instanceof NameExpr){
						if(((NameExpr)be.left()).myDecl instanceof ClassDecl){
							Error.error(be,"Left hand side of instanceof cannot be a class.");
							//Error.error(be,"Left hand side of instanceof needs expression of class type");
						}

						//CHECK THE myDecl as in the == or != case
						//if (((NameExpr)be.right()).name().getname() /*FINISH THIS*/ ) {
						if (((NameExpr)be.right()).myDecl instanceof ClassDecl) {
							if (lType instanceof ClassType) {
								be.type = new PrimitiveType(PrimitiveType.BooleanKind);
								break;
							}
							else{
								Error.error(be,"Left hand side of instanceof needs expression of class type");
							}
						}
						else{
							Error.error(be,"\'" + ((NameExpr)be.right()).name().getname() + "\' is not a class name.");
						}


					}
					else if (be.left() instanceof CastExpr){
						if (((NameExpr)be.right()).myDecl instanceof ClassDecl) {
							if (lType instanceof ClassType) {
								be.type = new PrimitiveType(PrimitiveType.BooleanKind);
								break;
							}
							else{
								Error.error(be,"Left hand side of instanceof needs expression of class type");
							}
						}
						else{
							Error.error(be,"\'" + ((NameExpr)be.right()).name().getname() + "\' is not a class name.");
						}
					}


				}
				Error.error(be,"Binary Expr Error");
			}
			default: {
				Error.error(be,"Binary Expr Error");
			}
			
		}


		println(be.line + ": Binary Expression has type: " + be.type);
		return be.type;
	}

    /** CAST EXPRESSION */
    public Object visitCastExpr(CastExpr ce) {
		println(ce.line + ": Visiting a cast expression");

		// YOUR CODE HERE
		//ASK

		Type ct = (Type)ce.type();
		Type expr = (Type)ce.expr().visit(this);

		//System.out.println(ct == null);
		//System.out.println(expr == null);
		//Can also have both numeric

		if(ce.expr() instanceof NameExpr){
			if ( ((NameExpr)ce.expr()).myDecl instanceof ClassDecl){
				Error.error(ce,"Cannot use class name \'" + ((ClassDecl) ((NameExpr)ce.expr()).myDecl).name() + "\'. Object name expected in cast.");
			}
		}

		if ((ct instanceof ClassType) && (expr instanceof ClassType)){
			if ((Type.isSuper((ClassType)ct, (ClassType)expr)) || (Type.isSuper((ClassType)expr, (ClassType)ct))){
				ce.type = ct;
			}
			else{
				Error.error(ce,"Illegal type cast. Cannot cast type \'" + expr.typeName() + "\' to type \'" + ct.typeName() + "\'.");
			}
		}
		else if(ct.isNumericType() && expr.isNumericType()){
			ce.type = ct;
		}
		else{
			Error.error(ce,"Illegal type cast. Cannot cast type \'" + expr.typeName() + "\' to type \'" + ct.typeName() + "\'.");
		}

		println(ce.line + ": Cast Expression has type: " + ce.type);
		return ce.type;
    }

    /** CLASSTYPE */
    public Object visitClassType(ClassType ct) {
		println(ct.line + ": Visiting a class type");

		println(ct.line + ": Class Type has type: " + ct);
		return ct;
    }

    /** CONSTRUCTOR (EXPLICIT) INVOCATION */
    public Object visitCInvocation(CInvocation ci) {
		println(ci.line + ": Visiting an explicit constructor invocation");

		// YOUR CODE HERE
		//ASK

		ClassDecl targetClass = null;

		// YOUR CODE HERE
		if (ci.superConstructorCall()){
			if (currentClass.superClass() != null){
				targetClass = currentClass.superClass().myDecl;
			}
			else{
				Error.error(ci, "CInvocation error");
			}
		}
		else{
			targetClass = currentClass;
		}


		ci.args().visit(this);

		if(targetClass.isInterface()){
			Error.error(ci, "Cannot instantiate interface \'" + targetClass.name() + "\'.");
		}


		ClassBodyDecl cbd = findMethod(targetClass.constructors, targetClass.className().getname(),
				ci.args(), false);

		if (cbd == null){
			Error.error(ci, "CInvocation error");
		}
		else{
			ci.constructor = (ConstructorDecl)cbd;
			ci.targetClass = targetClass;
		}

		return null;
    }

    /** CLASS DECLARATION */
    public Object visitClassDecl(ClassDecl cd) {
		println(cd.line + ": Visiting a class declaration " + cd.name());

		// YOUR CODE HERE

		//ASK
		currentClass = cd;
		super.visitClassDecl(cd);


		return null;
    }

    /** CONSTRUCTOR DECLARATION */
    public Object visitConstructorDecl(ConstructorDecl cd) {
		println(cd.line + ": Visiting a constructor declaration");

		// YOUR CODE HERE
		//ASK
		currentContext = cd;
		super.visitConstructorDecl(cd);

		return null;
    }

    /** DO STATEMENT */
    public Object visitDoStat(DoStat ds) {
		println(ds.line + ": Visiting a do statement");

		// YOUR CODE HERE
		Type expr = (Type)ds.expr().visit(this);

		if (!expr.isBooleanType()){
			//Error.error(ds, "DoStat error");
			Error.error(ds, "Non boolean Expression found as test in do-statement.");

		}

		ds.stat().visit(this);

		return null;
    }

    /** FIELD DECLARATION */
    public Object visitFieldDecl(FieldDecl fd) {
		println(fd.line + ": Visiting a field declaration");

		// Update the current context
		currentContext = fd;
		inFieldInit = true;
		currentFieldDecl = fd;
		//if (fd.var().init() != null)
			fd.var().visit(this);
		currentFieldDecl = null;
		inFieldInit = false;
		return fd.type();
    }

    /** FIELD REFERENCE */
    public Object visitFieldRef(FieldRef fr) {
		println(fr.line + ": Visiting a field reference" + fr.target());

		Type targetType = (Type) fr.target().visit(this);
		String field    = fr.fieldName().getname();

		// Changed June 22 2012 ARRAY
		if (fr.fieldName().getname().equals("length")) {
			if (targetType.isArrayType()) {
			fr.type = new PrimitiveType(PrimitiveType.IntKind);
			println(fr.line + ": Field Reference was a an Array.length reference, and it has type: " + fr.type);
			fr.targetType = targetType;
			return fr.type;
			}
		}

		if (targetType.isClassType()) {
			ClassType c = (ClassType)targetType;
			ClassDecl cd = c.myDecl;
			fr.targetType = targetType;

			println(fr.line + ": FieldRef: Looking up symbol '" + field + "' in fieldTable of class '" +
				c.typeName() + "'.");

			// Lookup field in the field table of the class associated with the target.
			FieldDecl lookup = (FieldDecl) NameChecker.NameChecker.getField(field, cd);

			// Field not found in class.
			if (lookup == null)
			Error.error(fr,"Field '" + field + "' not found in class '" + cd.name() + "'.");
			else {
			fr.myDecl = lookup;
			fr.type = lookup.type();
			}
		} else
			Error.error(fr,"Attempt to access field '" + field + "' in something not of class type.");
		println(fr.line + ": Field Reference has type: " + fr.type);

		if (inFieldInit && currentFieldDecl.fieldNumber <= fr.myDecl.fieldNumber && currentClass.name().equals(   (((ClassType)fr.targetType).myDecl).name()))
			Error.error(fr,"Illegal forward reference of non-initialized field.");

		return fr.type;
    }

    /** FOR STATEMENT */
    public Object visitForStat(ForStat fs) {
		println(fs.line + ": Visiting a for statement");

		// YOUR CODE HERE
		//ASK

		//check if expr, init, and incr are null
		//DON'T visit if null

		if (fs.init() != null){
			fs.init().visit(this);
		}

		if (fs.expr() != null){
			Type expr = (Type)fs.expr().visit(this);
			if (!expr.isBooleanType()){
				Error.error(fs, "Non boolean Expression found in for-statement.");
			}
		}


		if (fs.incr() != null){
			fs.incr().visit(this);
		}
		if (fs.stats() != null){
			fs.stats().visit(this);
		}

		return null;
    }

    /** IF STATEMENT */
    public Object visitIfStat(IfStat is) {
		println(is.line + ": Visiting a if statement");

		// YOUR CODE HERE
		Type expr = (Type)is.expr().visit(this);
		if (!expr.isBooleanType()){
			Error.error(is, "Non boolean Expression found as test in if-statement.");
		}

		is.thenpart().visit(this);
		if (is.elsepart() != null){
			is.elsepart().visit(this);
		}

		return null;
    }

    /** INVOCATION */
    public Object visitInvocation(Invocation in) {

    	//CHECK CALL .length() on String, no parameters
		//AND .charat(x) on String, x has to be integral, only one parameter


		println(in.line + ": Visiting an Invocation");

		ClassDecl targetClass;

		// YOUR CODE HERE
		if (in.target() == null){
			targetClass = currentClass;
		}
		else{
			Type target = (Type)in.target().visit(this);
			//check if it's a String here

			if (target.isStringType()){
				if (in.methodName().getname().equals("length") && (in.params().nchildren == 0)){
					in.type = new PrimitiveType(PrimitiveType.IntKind);
					return in.type;
				}
				else if(in.methodName().getname().equals("charat") && (in.params().nchildren == 1)){
					in.params().visit(this);

					if( ((Expression)(in.params().children[0])).type.isIntegralType() ){
						//ASK
						in.type = new PrimitiveType(PrimitiveType.IntKind);
						return in.type;
					}
					else{
						Error.error(in, "Invocation error");
					}
				}
				else{
					Error.error(in, "Invocation error");
				}
			}

			if (!(target instanceof ClassType)){
				Error.error(in, "Attempt to invoke method \'" + in.methodName().getname() + "\' in something not of class type.");
			}
			targetClass = ((ClassType)target).myDecl;
		}

		in.params().visit(this);


		ClassBodyDecl cbd = findMethod(targetClass.allMethods, in.methodName().getname(),
				in.params(), true);

		if (cbd == null){
			Error.error(in, "Invocation error");
		}
		else{
			in.targetMethod = (MethodDecl)cbd;
			in.type = ((MethodDecl)cbd).returnType();
		}


		println(in.line + ": Invocation has type: " + in.type);
		return in.type;
    }

    /** LITERAL */
    public Object visitLiteral(Literal li) {
		println(li.line + ": Visiting a literal");

		// YOUR CODE HERE
		switch(li.getKind()){
			case Literal.BooleanKind: {
				li.type = new PrimitiveType(Literal.BooleanKind);
				break;
			}
			case Literal.ByteKind: {
				li.type = new PrimitiveType(Literal.ByteKind);
				break;
			}
			case Literal.ShortKind: {
				li.type = new PrimitiveType(Literal.ShortKind);
				break;
			}
			case Literal.CharKind: {
				li.type = new PrimitiveType(Literal.CharKind);
				break;
			}
			case Literal.IntKind: {
				li.type = new PrimitiveType(Literal.IntKind);
				break;
			}
			case Literal.LongKind: {
				li.type = new PrimitiveType(Literal.LongKind);
				break;
			}
			case Literal.FloatKind: {
				li.type = new PrimitiveType(Literal.FloatKind);
				break;
			}
			case Literal.DoubleKind: {
				li.type = new PrimitiveType(Literal.DoubleKind);
				break;
			}
			case Literal.StringKind: {
				li.type = new PrimitiveType(Literal.StringKind);
				break;
			}
			case Literal.NullKind: {
				li.type = new NullType(li);
				break;
			}
			default: {
				Error.error(li, "Literal error");
			}
		}

		println(li.line + ": Literal has type: " + li.type);
		return li.type;
    }

    /** METHOD DECLARATION */
    public Object visitMethodDecl(MethodDecl md) {
		println(md.line + ": Visiting a method declaration");
		currentContext = md;

		// YOUR CODE HERE
		super.visitMethodDecl(md);

		return null;
    }

    /** NAME EXPRESSION */
    public Object visitNameExpr(NameExpr ne) {
		println(ne.line + ": Visiting a Name Expression");

		// YOUR CODE HERE
		//ASK myDecl already set???? Yes
		if (ne.myDecl instanceof ClassDecl){
			ne.type = new ClassType(((ClassDecl)ne.myDecl).className());
			((ClassType)ne.type).myDecl = (ClassDecl)ne.myDecl;
		}
		else if(ne.myDecl instanceof LocalDecl){
			ne.type = ((LocalDecl)ne.myDecl).type();
		}
		else if(ne.myDecl instanceof ParamDecl){
			ne.type = ((ParamDecl)ne.myDecl).type();
		}
		//else if(ne.myDecl instanceof FieldDecl){
		//	ne.type = ((FieldDecl)ne.myDecl).type();
		//}
		else{
			Error.error(ne, "NameExpr error");
		}

		println(ne.line + ": Name Expression has type: " + ne.type);
		return ne.type;
    }

    /** NEW */
    public Object visitNew(New ne) {
		println(ne.line + ": Visiting a new");

		// YOUR CODE HERE
		ClassDecl newClass = ne.type().myDecl;

		// YOUR CODE HERE

		ne.type().visit(this);
		ne.args().visit(this);

		if(newClass.isInterface()){
			Error.error(ne, "Cannot instantiate interface \'" + newClass.name() + "\'.");
		}
		//Ask lookingForMethods set to true or false?
		ClassBodyDecl cbd = findMethod(newClass.constructors, ne.type().name().getname(),
				ne.args(), false);
		//ClassBodyDecl cbd = findMethod(newClass.constructors, ne.type().typeName(),
		//		ne.args(), true);

		if (cbd == null){
			Error.error(ne, "New error");
		}
		else{
			ne.setConstructorDecl((ConstructorDecl)cbd);
			ne.type = ne.type();
		}

		println(ne.line + ": New has type: " + ne.type);
		return ne.type;
    }


    /** RETURN STATEMENT */
    public Object visitReturnStat(ReturnStat rs) {
	println(rs.line + ": Visiting a return statement");
	Type returnType;

	if (currentContext instanceof MethodDecl)
	    returnType = ((MethodDecl)currentContext).returnType();
	else
	    returnType = null;

	// Check is there is a return in a Static Initializer
	if (currentContext instanceof StaticInitDecl) 
	    Error.error(rs,"return outside method");

	// Check if a void method is returning something.
	if (returnType == null || returnType.isVoidType()) {
	    if (rs.expr() != null)
		Error.error(rs, "Return statement of a void function cannot return a value.");
	    return null;
	}

	// Check if a non void method is returning without a proper value.
	if (rs.expr() == null)
	    Error.error(rs, "Non void function must return a value.");

	Type returnValueType = (Type) rs.expr().visit(this);	
	if (rs.expr().isConstant()) {
	    if (returnType.isShortType() && Literal.isShortValue(((BigDecimal)rs.expr().constantValue()).longValue()))
		;// is ok break;                                                                                                    
	    else if (returnType.isByteType() && Literal.isByteValue(((BigDecimal)rs.expr().constantValue()).longValue()))
		; // is ok break;                                                                                                   
	    else if (returnType.isCharType() && Literal.isCharValue(((BigDecimal)rs.expr().constantValue()).longValue()))
		; // break;
	    else if (!Type.assignmentCompatible(returnType,returnValueType))
		Error.error(rs, "Illegal value of type " + returnValueType.typeName() + 
			    " in method expecting value of type " + returnType.typeName() + ".");
	} else if (!Type.assignmentCompatible(returnType,returnValueType))
	    Error.error(rs, "Illegal value of type " + returnValueType.typeName() + 
			" in method expecting value of type " + returnType.typeName() + ".");
		
	rs.setType(returnType);
	return null;
    }

    /** STATIC INITIALIZER */
    public Object visitStaticInitDecl(StaticInitDecl si) {
		println(si.line + ": Visiting a static initializer");

		// YOUR CODE HERE
		//ASK
		currentContext = si;
		super.visitStaticInitDecl(si);

		return null;
    }

    /** SUPER */
    public Object visitSuper(Super su) {
		println(su.line + ": Visiting a super");

		// YOUR CODE HERE
		if(currentClass.superClass() != null){
			su.type = currentClass.superClass();
		}
		else{
			Error.error(su, "Super error");
		}

		println(su.line + ": Super has type: " + su.type);

		return su.type;
    }

    /** SWITCH STATEMENT */
    public Object visitSwitchStat(SwitchStat ss) {
		println(ss.line + ": Visiting a Switch statement");


		Type exprType = (Type)ss.expr().visit(this);

		if(!exprType.isIntegralType() && !exprType.isStringType()){
			Error.error(ss, "Switch statement expects integer or string type." + exprType);
		}

		Sequence caseSequence = new Sequence();
		int defaultCounter = 0;
		boolean duplicateFlag = false;

		for (int i = 0; i < ss.switchBlocks().nchildren; i++){
			SwitchGroup currSwitchGroup = (SwitchGroup)ss.switchBlocks().children[i];

			for (int j = 0; j < currSwitchGroup.labels().nchildren; j++){
				SwitchLabel currSwitchLabel = (SwitchLabel)currSwitchGroup.labels().children[j];


				if (!currSwitchLabel.isDefault()) {
					if(!currSwitchLabel.expr().isConstant()){
						Error.error(ss, "Switch labels must be constants.");
					}

					Type const_exprType = (Type) currSwitchLabel.expr().visit(this);

					//System.out.println(exprType == null);
					//System.out.println(const_exprType== null);
					if (!Type.assignmentCompatible(exprType, const_exprType)) {
						if(exprType.isIntegralType()){
							Error.error(ss, "Switch labels must be of type int.");
						}
						else{
							Error.error(ss, "SwitchStat error");
						}
					}

					if (currSwitchLabel.expr() instanceof Literal){
						for(int k = 0; k < caseSequence.nchildren; k++){
							Literal currLiteral = (Literal)caseSequence.children[k];
							if(((Literal)currSwitchLabel.expr()).getText().equals(currLiteral.getText())){
								//Error.error(ss, "Duplicate case label.");
								duplicateFlag = true;
							}
						}
						caseSequence.append(currSwitchLabel.expr());
					}

				}
				else{
					defaultCounter++;
				}
			}

			currSwitchGroup.statements().visit(this);
			if(duplicateFlag){
				Error.error(ss, "Duplicate case label.");
			}
			if(defaultCounter > 1){
				Error.error(ss, "Duplicate default label.");
			}
		}


		return null;
    }

    /** TERNARY EXPRESSION */
    public Object visitTernary(Ternary te) {
		println(te.line + ": Visiting a ternary expression");

		// YOUR CODE HERE
		Type exprType = (Type)te.expr().visit(this);

		Type trueBranchType = (Type)te.trueBranch().visit(this);
		Type falseBranchType = (Type)te.falseBranch().visit(this);

		if(!exprType.isBooleanType()){
			Error.error(te, "Non boolean Expression found as test in ternary expression.");
		}


		/*
		Boolean b = (Boolean)te.expr().constantValue();
		if(b.booleanValue()){
			te.type = trueBranchType;
		}
		else{
			te.type = falseBranchType;
		}
		*/

		if((trueBranchType instanceof PrimitiveType) && (falseBranchType instanceof PrimitiveType)){
			te.type = PrimitiveType.ceilingType((PrimitiveType)trueBranchType, (PrimitiveType)falseBranchType);
		}
		else if ((trueBranchType instanceof ClassType) && (falseBranchType instanceof ClassType)){
			if (Type.isSuper((ClassType)trueBranchType, (ClassType)falseBranchType)){
				te.type = trueBranchType;
			}
			else if(Type.isSuper((ClassType)falseBranchType, (ClassType)trueBranchType)){
				te.type = falseBranchType;
			}
			else{
				Error.error(te, "Ternary error");
			}
		}
		else{
			Error.error(te, "Ternary error");
		}

		println(te.line + ": Ternary has type: " + te.type);
		return te.type;
    }

    /** THIS */
    public Object visitThis(This th) {
	println(th.line + ": Visiting a this statement");

	th.type = th.type();

	println(th.line + ": This has type: " + th.type);
	return th.type;
    }

    /** UNARY POST EXPRESSION */
    public Object visitUnaryPostExpr(UnaryPostExpr up) {
		println(up.line + ": Visiting a unary post expression");

		Type eType = (Type)up.expr().visit(this);

		//ASK convert to int for byte, char, short
		// YOUR CODE HERE
		if((up.expr() instanceof FieldRef) || (up.expr() instanceof NameExpr)
				|| (up.expr() instanceof ArrayAccessExpr)){
			if(!(eType.isNumericType())){
				Error.error(up, "UnaryPostExpr error");
			}
			up.type = eType;
		}
		else{
			Error.error(up, "Variable expected, found value.");
		}


		println(up.line + ": Unary Post Expression has type: " + up.type);
		return eType;
    }

    /** UNARY PRE EXPRESSION */
    public Object visitUnaryPreExpr(UnaryPreExpr up) {
		println(up.line + ": Visiting a unary pre expression");

		// YOUR CODE HERE

		Type eType = (Type)up.expr().visit(this);

		//ASK set to int if byte, short, char

		switch(up.op().getKind()){

			case PreOp.PLUSPLUS:
			case PreOp.MINUSMINUS: {

				if((up.expr() instanceof FieldRef) || (up.expr() instanceof NameExpr)
						|| (up.expr() instanceof ArrayAccessExpr)){
					if(!(eType.isNumericType())){
						Error.error(up, "Cannot apply operator \'" + up.op().operator() + "\' to something of type " + eType.typeName() + ".");
					}
					up.type = eType;
				}
				else{
					Error.error(up, "Variable expected, found value.");
				}

			}

			case PreOp.PLUS:
			case PreOp.MINUS: {
				if(eType.isNumericType()){
					up.type = eType;
				}
				else{
					Error.error(up, "Cannot apply operator \'" + up.op().operator() + "\' to something of type " + eType.typeName() + ".");
				}
				break;
			}
			case PreOp.NOT: {
				if(eType.isBooleanType()){
					up.type = eType;
				}
				else{
					Error.error(up, "Cannot apply operator \'" + up.op().operator() + "\' to something of type " + eType.typeName() + ".");
				}
				break;
			}
			case PreOp.COMP: {
				if(eType.isIntegralType()){
					up.type = eType;
				}
				else{
					Error.error(up, "Cannot apply operator \'" + up.op().operator() + "\' to something of type " + eType.typeName() + ".");
				}
				break;
			}
			default: {
				Error.error(up, "Cannot apply operator \'" + up.op().operator() + "\' to something of type " + eType.typeName() + ".");
				break;
			}
		}

		if(up.type.isByteType() || up.type.isCharType() || up.type.isShortType()){
			up.type = new PrimitiveType(PrimitiveType.IntKind);
		}

		println(up.line + ": Unary Pre Expression has type: " + up.type);
		return up.type;
    }

    /** VAR */
    public Object visitVar(Var va) {
		println(va.line + ": Visiting a var");

		//ASK is myDecl in Var already set in name resolution pass????? Yes
		// YOUR CODE HERE

		Type varType = (Type)va.myDecl.type();

		if(va.init() != null) {
			Type initType = (Type) va.init().visit(this);

			if (!(Type.assignmentCompatible(varType, initType))) {
				Error.error(va, "Cannot assign value of type " + initType.typeName() + " to variable of type " + varType.typeName() + ".");
			}

			//if(initType instanceof ClassType){
			//	if(((ClassType) initType).myDecl.isInterface()){
			//		Error.error(va, "Cannot instantiate interface \'" + ((ClassType) initType).myDecl.name() + "\'.");
			//	}
			//}
		}

		return null;
    }

    /** WHILE STATEMENT */
    public Object visitWhileStat(WhileStat ws) {
		println(ws.line + ": Visiting a while statement");

		// YOUR CODE HERE

		Type eType = (Type)ws.expr().visit(this);

		if (!(eType.isBooleanType())){
			Error.error(ws, "Non boolean Expression found as test in while-statement.");
		}

		ws.stat().visit(this);

		return null;
    }

}
