package CodeGenerator;

import AST.*;
import Utilities.Visitor;

class AllocateAddresses extends Visitor {

	private Generator gen;
	private ClassDecl currentClass;
    private ClassBodyDecl currentBodyDecl;

	AllocateAddresses(Generator g, ClassDecl currentClass, boolean debug) {
		this.debug = debug;
		gen = g;
		this.currentClass = currentClass;
	}
    
    public Object visitBlock(Block bl) {
	int tempAddress = gen.getAddress();
	bl.visitChildren(this);
	gen.setAddress(tempAddress);
	return null;
    }
    

	// LOCAL VARIABLE DECLARATION
	public Object visitLocalDecl(LocalDecl ld) {
		// YOUR CODE HERE
		ld.address = gen.getAddress();
		if (ld.type().isDoubleType() || ld.type().isLongType()) {
			gen.inc2Address();
		} else {
			gen.incAddress();
		}
		println(ld.line + ": LocalDecl:\tAssigning address:  " + ld.address + " to local variable '" + ld.var().name().getname() + "'.");
		return null;
	}

	// PARAMETER DECLARATION
	public Object visitParamDecl(ParamDecl pd) {
		// YOUR CODE HERE
		pd.address = gen.getAddress();
		if (pd.type().isDoubleType() || pd.type().isLongType()) {
			gen.inc2Address();
		} else {
			gen.incAddress();
		}
		println(pd.line + ": ParamDecl:\tAssigning address:  " + pd.address + " to parameter '" + pd.paramName().getname() + "'.");
		return null;
	}

	// METHOD DECLARATION
	public Object visitMethodDecl(MethodDecl md) {
		println(md.line + ": MethodDecl:\tResetting address counter for method '" + md.name().getname() + "'.");
		// YOUR CODE HERE
		// ask start of method

		gen.resetAddress();

		if (md.getModifiers().isStatic()) {
			gen.setAddress(0);
		} else {
			gen.setAddress(1);
		}
		
		currentBodyDecl = md;
		super.visitMethodDecl(md);
		md.localsUsed = gen.getLocalsUsed();
		//gen.endMethod(md);

		println(md.line + ": End MethodDecl");	
		return null;
	}

	// CONSTRUCTOR DECLARATION
	public Object visitConstructorDecl(ConstructorDecl cd) {	
		println(cd.line + ": ConstructorDecl:\tResetting address counter for constructor '" + cd.name().getname() + "'.");
		gen.resetAddress();
		gen.setAddress(1);
		currentBodyDecl = cd;
		super.visitConstructorDecl(cd);
		cd.localsUsed = gen.getLocalsUsed();
		//System.out.println("Locals Used: " + cd.localsUsed);
		println(cd.line + ": End ConstructorDecl");
		return null;
	}

	// STATIC INITIALIZER
	public Object visitStaticInitDecl(StaticInitDecl si) {
		println(si.line + ": StaticInit:\tResetting address counter for static initializer for class '" + currentClass.name() + "'.");
		// YOUR CODE HERE
		gen.resetAddress();
		gen.setAddress(0);
		currentBodyDecl = si;
		super.visitStaticInitDecl(si);
		si.localsUsed = gen.getLocalsUsed();
		println(si.line + ": End StaticInit");
		return null;
	}
}

