package ModifierChecker;

import AST.*;
import Utilities.*;
import NameChecker.*;
import TypeChecker.*;
import Utilities.Error;

public class ModifierChecker extends Visitor {

	private SymbolTable classTable;
	private ClassDecl currentClass;
	private ClassBodyDecl currentContext;
	private boolean leftHandSide = false;

	public ModifierChecker(SymbolTable classTable, boolean debug) {
		this.classTable = classTable;
		this.debug = debug;
	}

	/** Assignment */
	public Object visitAssignment(Assignment as) {
	    println(as.line + ": Visiting an assignment (Operator: " + as.op()+ ")");

		boolean oldLeftHandSide = leftHandSide;

		leftHandSide = true;
		as.left().visit(this);

		// Added 06/28/2012 - no assigning to the 'length' field of an array type
		if (as.left() instanceof FieldRef) {
			FieldRef fr = (FieldRef)as.left();
			if (fr.target().type.isArrayType() && fr.fieldName().getname().equals("length"))
				Error.error(fr,"Cannot assign a value to final variable length.");
		}

		leftHandSide = oldLeftHandSide;
		as.right().visit(this);


		return null;
	}

	/** CInvocation */
	public Object visitCInvocation(CInvocation ci) {
	    println(ci.line + ": Visiting an explicit constructor invocation (" + (ci.superConstructorCall() ? "super" : "this") + ").");

		// YOUR CODE HERE

		if (ci.superConstructorCall()) {
			// ask how to find specific constructor
			if (ci.constructor.getModifiers().isPrivate()) {
				Error.error(ci, "CInvocation Error");
			}
			else if (currentContext.isStatic()){
				Error.error(ci, "CInvocation Error");
			}
		}
		super.visitCInvocation(ci);
		return null;
	}

	/** ClassDecl */
	public Object visitClassDecl(ClassDecl cd) {
		println(cd.line + ": Visiting a class declaration for class '" + cd.name() + "'.");

		currentClass = cd;

		// If this class has not yet been declared public make it so.
		if (!cd.modifiers.isPublic())
			cd.modifiers.set(true, false, new Modifier(Modifier.Public));

		// If this is an interface declare it abstract!
		if (cd.isInterface() && !cd.modifiers.isAbstract())
			cd.modifiers.set(false, false, new Modifier(Modifier.Abstract));

		// If this class extends another class then make sure it wasn't declared
		// final.
		if (cd.superClass() != null)
			if (cd.superClass().myDecl.modifiers.isFinal())
				Error.error(cd, "Class '" + cd.name()
						+ "' cannot inherit from final class '"
						+ cd.superClass().typeName() + "'.");

		// YOUR CODE HERE
		super.visitClassDecl(cd);
		return null;
	}

	/** FieldDecl */
	public Object visitFieldDecl(FieldDecl fd) {
	    println(fd.line + ": Visiting a field declaration for field '" +fd.var().name() + "'.");

		// If field is not private and hasn't been declared public make it so.
		if (!fd.modifiers.isPrivate() && !fd.modifiers.isPublic())
			fd.modifiers.set(false, false, new Modifier(Modifier.Public));

		// YOUR CODE HERE
		if (fd.modifiers.isFinal()) {
			if (fd.var().init() == null) {
				Error.error(fd, "FieldDecl Error");
			}
		}
		super.visitFieldDecl(fd);
		return null;
	}

	/** FieldRef */
	public Object visitFieldRef(FieldRef fr) {
	    println(fr.line + ": Visiting a field reference '" + fr.fieldName() + "'.");

		// YOUR CODE HERE

		if(leftHandSide && fr.myDecl.modifiers.isFinal()){
			Error.error(fr, "Cannot assign a value to final field \'" + fr.myDecl.getname() + "\'.");
		}

		if (currentContext.isStatic()) {

			if (fr.target() != null) {
				if (fr.target() instanceof This && fr.rewritten) {
					Error.error(fr, "non-static field \'" + fr.myDecl.getname() + "\' cannot be referenced from a static context.");
				}
			}

		}

		if(fr.target() != null){
			ClassType targetClassType = (ClassType) fr.targetType;
			if (fr.myDecl.modifiers.isPrivate()) {
				if (!(currentClass == targetClassType.myDecl)) {
					Error.error(fr, "field \'" + fr.myDecl.getname() + "\' was declared 'private' and cannot be accessed outside its class.");
				}
			}

			if(!fr.myDecl.modifiers.isStatic() && fr.target() instanceof NameExpr){
				if(((NameExpr)fr.target()).myDecl instanceof ClassDecl){
					Error.error(fr, "non-static field \'" + fr.myDecl.getname() + "\' cannot be referenced in a static context.");
				}
			}

		}

		if(!fr.rewritten) {
			fr.target().visit(this);
		}

		return null;
	}

	/** MethodDecl */
	public Object visitMethodDecl(MethodDecl md) {
	    println(md.line + ": Visiting a method declaration for method '" + md.name() + "'.");

		// YOUR CODE HERE

		if (md.getModifiers().isAbstract()) {
			if (!md.getMyClass().modifiers.isAbstract()) {
				Error.error(md.getMyClass(), "class \'" + md.getMyClass().name() + "\' is not abstract and does not override abstract methods:", false);
				Error.error(md, md.returnType().typeName() + " " + md.getname() + "(" + Type.parseSignature(md.paramSignature()) + " )");
			} else if (md.getModifiers().isFinal()) {
				Error.error(md, "Abstract and final method decl error");
			} else if (md.getModifiers().isStatic()) {
				Error.error(md, "Abstract but static method decl error");
			}
		}

		if(md.getMyClass().isInterface()){
			if(md.getModifiers().isFinal()){
				Error.error(md, "Method \'" + md.getname() + "\' cannot be declared final in an interface.");
			}
		}

		TypeChecker tc = new TypeChecker(this.classTable, true);

		if (!currentClass.isInterface()) {
			if (md.block() == null) {
				if (!md.getModifiers().isAbstract()) {
					Error.error(md, "Method \'" + md.getname() + "\' does not have a body, or should be declared abstract.");
				}
			}
		}

		if (currentClass.superClass() != null) {
			MethodDecl matchingDecl = (MethodDecl) tc.findMethod(currentClass.superClass().myDecl.allMethods, md.getname(), md.params(), true);
			if (matchingDecl != null) {
				if(matchingDecl.paramSignature().equals(md.paramSignature())) {
					if (matchingDecl.getModifiers().isStatic() && !md.getModifiers().isStatic()) {
						Error.error(md, "Method \'" + md.getname() + "\' declared static in superclass, cannot be reimplemented non-static.");
					} else if (!matchingDecl.getModifiers().isStatic() && md.getModifiers().isStatic()) {
						Error.error(md, "Method \'" + md.getname() + "\' declared non-static in superclass, cannot be reimplemented static.");
					} else if (matchingDecl.getModifiers().isFinal()) {
						Error.error(md, "Method \'" + md.getname() + "\' was implemented as final in super class, cannot be reimplemented.");
					}
				}
			}
		}



		currentContext = md;
		super.visitMethodDecl(md);
		return null;
	}



	/** Invocation */
	public Object visitInvocation(Invocation in) {
	    println(in.line + ": Visiting an invocation of method '" + in.methodName() + "'.");

		// YOUR CODE HERE

		super.visitInvocation(in);
		if(in.target() != null) {
			if (in.target() instanceof NameExpr) {
				if (((NameExpr) in.target()).myDecl instanceof ClassDecl) {
					if (!in.targetMethod.getModifiers().isStatic()) {
						Error.error(in, "non-static method \'" + in.targetMethod.getname() + "\' cannot be referenced from a static context.");
					}
				}
			}
			if (in.targetMethod.getModifiers().isPrivate()) {
				ClassType targetClassType = (ClassType) in.targetType;
				if (!(currentClass == targetClassType.myDecl)) {
					Error.error(in, in.targetMethod.getname() + "(" + Type.parseSignature(in.targetMethod.paramSignature()) + " )" + " has private access in \'" + in.targetMethod.getMyClass().name() + "\'.");
				}
			}
		}
		else {

			if (currentContext.isStatic()) {
				if (!in.targetMethod.getModifiers().isStatic()) {
					Error.error(in, "non-static method \'" + in.targetMethod.getname() + "\' cannot be referenced from a static context.");
				}
			}

			if (currentContext.isStatic()) {
				if (in.target() != null) {
					if ((in.target() instanceof Super) || (in.target() instanceof This)) {
						Error.error(in, "Invocation Error");
					}
				}
			}
		}

		return null;
	}


	public Object visitNameExpr(NameExpr ne) {
	    println(ne.line + ": Visiting a name expression '" + ne.name() + "'. (Nothing to do!)");
	    return null;
	}

	/** ConstructorDecl */
	public Object visitConstructorDecl(ConstructorDecl cd) {
	    println(cd.line + ": visiting a constructor declaration for class '" + cd.name() + "'.");

		// YOUR CODE HERE

		currentContext = cd;
		super.visitConstructorDecl(cd);
		return null;
	}

	/** New */
	public Object visitNew(New ne) {
	    println(ne.line + ": visiting a new '" + ne.type().myDecl.name() + "'.");

		// YOUR CODE HERE
		if (ne.type().myDecl.modifiers.isAbstract()) {
			Error.error(ne, "Cannot instantiate abstract class \'" + ne.type().myDecl.name() + "\'.");
		}

		if(!currentClass.name().equals(ne.type().myDecl.name())) {
			if (ne.getConstructorDecl().getModifiers().isPrivate()) {
				Error.error(ne, ne.getConstructorDecl().getname() + "(" + Type.parseSignature(ne.getConstructorDecl().paramSignature()) + " )" + " has private access in \'" + ne.getConstructorDecl().getname() + "\'.");
			}
		}

		super.visitNew(ne);
		return null;
	}

	/** StaticInit */
	public Object visitStaticInitDecl(StaticInitDecl si) {
		println(si.line + ": visiting a static initializer");

		// YOUR CODE HERE

		currentContext = si;
		super.visitStaticInitDecl(si);
		return null;
	}

	/** Super */
	public Object visitSuper(Super su) {
		println(su.line + ": visiting a super");

		if (currentContext.isStatic())
			Error.error(su,
					"non-static variable super cannot be referenced from a static context");

		return null;
	}

	/** This */
	public Object visitThis(This th) {
		println(th.line + ": visiting a this");

		if (currentContext.isStatic())
			Error.error(th,	"non-static variable this cannot be referenced from a static context");

		return null;
	}

	/** UnaryPostExpression */
	public Object visitUnaryPostExpr(UnaryPostExpr up) {
		println(up.line + ": visiting a unary post expression with operator '" + up.op() + "'.");
	
		// YOUR CODE HERE
		up.expr().visit(this);
		if (up.expr() instanceof FieldRef) {
			if (((FieldRef)up.expr()).myDecl.modifiers.isFinal()) {
				Error.error(up, "Cannot assign a value to final field \'" + ((FieldRef)up.expr()).fieldName().getname() + "\'.");
			}
		}

		return null;
	}
    
	/** UnaryPreExpr */
	public Object visitUnaryPreExpr(UnaryPreExpr up) {
		println(up.line + ": visiting a unary pre expression with operator '" + up.op() + "'.");
	
		// YOUR CODE HERE
		up.expr().visit(this);
		if (up.expr() instanceof FieldRef) {
			if (((FieldRef)up.expr()).myDecl.modifiers.isFinal()) {
				Error.error(up, "Cannot assign a value to final field \'" + ((FieldRef)up.expr()).fieldName().getname() + "\'.");
			}
		}

		return null;
	}
}
