package NameChecker;

import AST.*;
import Utilities.Error;
import Utilities.SymbolTable;
import Utilities.Visitor;
import Utilities.Rewrite;

import java.sql.SQLOutput;
import java.util.*;
import Parser.*;;

public class NameChecker extends Visitor {

	/* getMethod traverses the class hierarchy to look for a method of
       name 'methodName'. We return #t if we find any method with the
       correct name. Since we don't have types yet we cannot look at
       the signature of the method, so all we do for now is look if
       any method is defined. The search is as follows:

       1) look in the current class
       2) look in its super class
       3) look in all the interfaces

       Remember that the an entry in the methodTable is a symbol table
       it self. It holds all entries of the same name, but with
       different signatures. (See Documentation)
	 */    
	public static SymbolTable getMethod(String methodName, ClassDecl cd) {

		// YOUR CODE HERE
		if ((cd.methodTable).get(methodName) != null) {
			return (SymbolTable)(cd.methodTable).get(methodName);
		}
		else {
			if (cd.superClass() != null){
				ClassType superClass = cd.superClass();
				SymbolTable superClassResult = getMethod(methodName, superClass.myDecl);
				if (superClassResult != null){
					return superClassResult;
				}
			}
			SymbolTable interfaceSearch;
			for(int i = 0; i < cd.interfaces().nchildren; i++){
				interfaceSearch = getMethod(methodName, ((ClassType)cd.interfaces().children[i]).myDecl);
				if (interfaceSearch != null){
      				return interfaceSearch;
    			}
  			}
		}
		return null;
	}

	/* Same as getMethod just for fields instead 
	 */
	public static AST getField(String fieldName, ClassDecl cd) {

		// YOUR CODE HERE
		if ((cd.fieldTable).get(fieldName) != null) {
			return (AST)(cd.fieldTable).get(fieldName);
		}
		else {
			if (cd.superClass() != null){
				ClassType superClass = cd.superClass();
				AST superClassResult = getField(fieldName, superClass.myDecl);
				if (superClassResult != null){
					return superClassResult;
				}
			}
			AST interfaceSearch;
			for(int i = 0; i < (cd.interfaces()).nchildren; i++){
				interfaceSearch = getField(fieldName, ((ClassType)cd.interfaces().children[i]).myDecl);
				if (interfaceSearch != null){
					return interfaceSearch;
				}
  			}
		}
		return null;
	}

	/* Traverses all the classes and interfaces and builds a sequence
	   of the methods and constructors of the class hierarchy.
	*/
	public void getClassHierarchyMethods(ClassDecl cd, Sequence lst, Hashtable<String, Object> seenClasses) {
		// YOUR CODE HERE
		Enumeration<String> methodNames = cd.methodTable.entries.keys();

		while (methodNames.hasMoreElements()) {
			String currentMethod = methodNames.nextElement();
			SymbolTable currentMethodTable = (SymbolTable)cd.methodTable.get(currentMethod);
			Enumeration<Object> methods = currentMethodTable.entries.elements();
			while (methods.hasMoreElements()) {
				AST currentMethodDecl = (AST)methods.nextElement();
				lst.append(currentMethodDecl);
			}
		}

		seenClasses.put(cd.name(), cd);

		if (cd.superClass() != null){
			ClassType superClass = cd.superClass();
			getClassHierarchyMethods(superClass.myDecl, lst, seenClasses);
		}

		for(int i = 0; i < (cd.interfaces()).nchildren; i++){
			getClassHierarchyMethods(((ClassType)cd.interfaces().children[i]).myDecl, lst, seenClasses);
		}

		cd.allMethods = lst;
	}

	/* For each method (not constructors) in this list, check that if
       it exists more than once with the same parameter signature that
       they all return something of the same type. 
	*/

		// ask about void return
	public void checkReturnTypesOfIdenticalMethods(Sequence lst) {
		// YOUR CODE HERE
		for (int i = 0; i < lst.nchildren; i++) {
			if (lst.children[i] instanceof MethodDecl){
				for (int j = 0; j < lst.nchildren; j++) {
					if (lst.children[j] instanceof MethodDecl){
						MethodDecl firstMethod = (MethodDecl)lst.children[i];
						MethodDecl secondMethod = (MethodDecl)lst.children[j];
						if (firstMethod != secondMethod) {
							if (firstMethod.getname().equals(secondMethod.getname()) && firstMethod.paramSignature().equals(secondMethod.paramSignature())) {
								if (!firstMethod.returnType().identical(secondMethod.returnType())) {
									Error.error("Method \'" + secondMethod.getname() + "\' has been declared with two different return types:", false);
									Error.error(secondMethod," " + secondMethod.returnType().typeName() + " " + secondMethod.getname() +  "(" + secondMethod.returnType().parseSignature(secondMethod.paramSignature()) + " )", false);
									Error.error(firstMethod," " + firstMethod.returnType().typeName() + " " + firstMethod.getname() + "(" + firstMethod.returnType().parseSignature(firstMethod.paramSignature()) + " )", true);
								}
							}
						}
					}
				}
			}
		}

	}
    
    /* Divides all the methods into two sequences: one for all the
       abstract ones, and one for all the concrete ones and check that
       all the methods that were delcared abstract were indeed
       implemented somewhere in the class hierarchy.  */
    

    // sup is the class in which the abstract method lives,
    // sub is the class in which the concrete method lives.
    public static boolean isSuper(ClassDecl sup, ClassDecl sub) {
	if (sup.name().equals(sub.name()))
	    return true;
		
	if (sub.superClass() != null && isSuper(sup, sub.superClass().myDecl))
	    return true;

	for (int i=0; i<sub.interfaces().nchildren; i++) 
	    if (isSuper(sup, ((ClassType)sub.interfaces().children[i]).myDecl))
		return true;

	return false;
    }


	public void checkImplementationOfAbstractClasses(ClassDecl cd, Sequence methods) {
		// YOUR CODE HERE

		for (int i = 0; i < methods.nchildren; i++) {
			if (methods.children[i] instanceof MethodDecl) {
				if(((MethodDecl)methods.children[i]).block() == null){
					cd.abstractMethods.append(methods.children[i]);
				}
				else{
					cd.concreteMethods.append(methods.children[i]);
				}
			}
		}


		boolean abstractMatchesConcrete = false;

		for (int i = 0; i < cd.abstractMethods.nchildren; i++) {
			abstractMatchesConcrete = false;
			for (int j = 0; j < cd.concreteMethods.nchildren; j++) {
				MethodDecl abstractMethod = (MethodDecl)cd.abstractMethods.children[i];
				MethodDecl concreteMethod = (MethodDecl)cd.concreteMethods.children[j];
				if (abstractMethod.getname().equals(concreteMethod.getname()) && abstractMethod.paramSignature().equals(concreteMethod.paramSignature())) {
					abstractMatchesConcrete = true;
					break;
				}
			}
			if (!abstractMatchesConcrete) {
				Error.error(cd, "class \'" + cd.className() + "\' is not abstract and does not override abstract methods:", false);
				MethodDecl abstractMethod = (MethodDecl)cd.abstractMethods.children[i];
				Error.error("  " + abstractMethod.returnType().typeName() + " " + abstractMethod.getname() +  "(" + abstractMethod.returnType().parseSignature(abstractMethod.paramSignature()) + " )", true);
			}
		}
	}
    
	public  void checkUniqueFields(Sequence fields, ClassDecl cd) {
		// YOUR CODE HERE
		Enumeration<Object> currentClassFields = cd.fieldTable.entries.elements();
		boolean matchesExistingField = false;

		while (currentClassFields.hasMoreElements()) {
			matchesExistingField = false;
			FieldDecl currentField = (FieldDecl)currentClassFields.nextElement();
			for (int i = 0; i < fields.nchildren; i++) {
				if (currentField.getname().equals(fields.children[i].getname())) {
					Error.error("Field \'" + currentField.getname() + "\' already defined.");
					matchesExistingField = true;
					break;
				}
			}
			
			if (!matchesExistingField) {
				fields.append(currentField);
			}

		}

		if (cd.superClass() != null){
			ClassType superClass = cd.superClass();
			checkUniqueFields(fields, superClass.myDecl);
		}

		for (int i = 0; i < (cd.interfaces()).nchildren; i++){
			checkUniqueFields(fields, ((ClassType)cd.interfaces().children[i]).myDecl);
		}
	
	}

	// %%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%
	// %%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%
	// %%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%

	/**
	 * Points to the current scope.
	 */
	private SymbolTable currentScope;
	/**
	 * The class table<br>
	 * This is set in the constructor.
	 */
	//ASK
	private SymbolTable classTable;
	/**
	 * The current class in which we are working.<br>
	 * This is set in visitClassDecl().
	 */
	private ClassDecl   currentClass;
	
	public NameChecker(SymbolTable classTable, boolean debug) { 
		this.classTable = classTable; 
		this.debug = debug;
	}

	/** BLOCK */
	public Object visitBlock(Block bl) {
		println("Block:\t\t Creating new scope for Block.");
		currentScope = currentScope.newScope();
		super.visitBlock(bl);
		currentScope = currentScope.closeScope(); 
		return null;
	}


	/** CLASS DECLARATION */
	public Object visitClassDecl(ClassDecl cd) {
		println("ClassDecl:\t Visiting class '"+cd.name()+"'");

		// If we use the field table here as the top scope, then we do not
		// need to look in the field table when we resolve NameExpr. Note,
		// at this time we have not yet rewritten NameExprs which are really
		// FieldRefs with a null target as we have not resolved anything yet.
		currentScope = cd.fieldTable;
		currentClass = cd;

		Hashtable<String, Object> seenClasses = new Hashtable<String, Object>();

		// Check that the superclass is a class.
		if (cd.superClass() != null) 
			if (cd.superClass().myDecl.isInterface())
				Error.error(cd,"Class '" + cd.name() + "' cannot inherit from interface '" +
						cd.superClass().myDecl.name() + "'.");



		if (cd.superClass() != null) {
			if (cd.name().equals(cd.superClass().typeName()))
				Error.error(cd, "Class '" + cd.name() + "' cannot extend itself.");
			// If a superclass has a private default constructor, the 
			// class cannot be extended.
			ClassDecl superClass = (ClassDecl)classTable.get(cd.superClass().typeName());
			SymbolTable st = (SymbolTable)superClass.methodTable.get("<init>");
			ConstructorDecl ccd = (ConstructorDecl)st.get("");
			if (ccd != null && ccd.getModifiers().isPrivate())
			    Error.error(cd, "Class '" + superClass.className().getname() + "' cannot be extended because it has a private default constructor.");
		}
		
		// Visit the children
		super.visitClassDecl(cd);
			
		currentScope = null;

		// Check that the interfaces implemented are interfaces.
		for (int i=0; i<cd.interfaces().nchildren; i++) {
			ClassType ct = (ClassType)cd.interfaces().children[i];
			if (ct.myDecl.isClass())
				Error.error(cd,"Class '" + cd.name() + "' cannot implement class '" + ct.name() + "'.");
		}

		Sequence methods = new Sequence();
		
		getClassHierarchyMethods(cd, methods, seenClasses);

		checkReturnTypesOfIdenticalMethods(methods);

		// If the class is not abstract and not an interface it must implement all
		// the abstract functions of its superclass(es) and its interfaces.
		if (!cd.isInterface() && !cd.modifiers.isAbstract()) {
			checkImplementationOfAbstractClasses(cd, methods);
			// checkImplementationOfAbstractClasses(cd, new Sequence());
		}
		// All field names can only be used once in a class hierarchy
		checkUniqueFields(new Sequence(), cd);

		cd.allMethods = methods; // now contains only MethodDecls

		// Fill cd.constructors.
		SymbolTable st = (SymbolTable)cd.methodTable.get("<init>");
		ConstructorDecl cod;
		if (st != null) {
			for (Enumeration<Object> e = st.entries.elements() ; 
					e.hasMoreElements(); ) {
				cod = (ConstructorDecl)e.nextElement();
				cd.constructors.append(cod);
			}
		}

		// needed for rewriting the tree to replace field references
		// represented by NameExpr.
		println("ClassDecl:\t Performing tree Rewrite on " + cd.name());
		new Rewrite().go(cd, cd);

		return null;
	}

	// YOUR CODE HERE
	public Object visitForStat(ForStat fs) {
		println("ForStat:\t Creating new scope for For Statement.");
		currentScope = currentScope.newScope();
		super.visitForStat(fs);
		currentScope = currentScope.closeScope(); 
		return null;
	}

	public Object visitConstructorDecl(ConstructorDecl cst) {
		println("ConstructorDecl: Creating new scope for constructor <init> with signature \'" + cst.paramSignature() + "\' (Parameters and Locals).");
		currentScope = currentScope.newScope();
		super.visitSequence(cst.params());
		currentScope = currentScope.newScope();
		if (cst.cinvocation() != null) {
			super.visitCInvocation(cst.cinvocation());
		}
		super.visitSequence(cst.body());
		currentScope = currentScope.closeScope(); 
		currentScope = currentScope.closeScope(); 
		return null;
	}

	public Object visitMethodDecl(MethodDecl md) {
		println("MethodDecl:\t Creating new scope for Method \'" + md.getname() +
				"\' with signature \'" + md.paramSignature() + "\' (Parameters and Locals).");
		currentScope = currentScope.newScope();
		super.visitMethodDecl(md);
		currentScope = currentScope.closeScope(); 
		return null;
	}

	public Object visitSwitchStat(SwitchStat ss) {
		//println("SwitchStat:\t Creating new scope for SwitchStat.");
		println("This:\t Visiting SwitchStat.");
		currentScope = currentScope.newScope();
		super.visitSwitchStat(ss);
		currentScope = currentScope.closeScope(); 
		return null;
	}

	public Object visitLocalDecl(LocalDecl ld) {
		println("LocalDecl:\t Declaring local symbol \'" + ld.name() + "\'.");
		currentScope.put(ld.name(), ld);
		super.visitLocalDecl(ld);
		return null;
	}

	public Object visitParamDecl(ParamDecl pd) {
		println("ParamDecl:\t Declaring parameter \'" + pd.name() + "\'.");
		currentScope.put(pd.name(), pd);
		super.visitParamDecl(pd);
		return null;
	}

	public Object visitInvocation(Invocation ivc) {
		///same as visitFieldRef but also check if target is null
		if ((ivc.target() instanceof This) || (ivc.target() == null)) {
			println("Invocation:\t Looking up method \'" + ivc.methodName().getname() + "\'.");
			SymbolTable findInvocation = getMethod(ivc.methodName().getname(), this.currentClass);
			if (findInvocation == null) {
				Error.error("Method \'" + ivc.methodName().getname() + "\' not found.");
			}
		}
		else{
			System.out.println("Invocation:\t Target too complicated for now!");
		}

		super.visitInvocation(ivc);

		return null;
	}

	public Object visitFieldRef(FieldRef fr) {
		if (fr.target() instanceof This) {
			println("FieldRef:\t Looking up field \'" + fr.fieldName().getname() + "\'.");
			AST findFieldRef = getField(fr.fieldName().getname(), this.currentClass);
			if (findFieldRef == null) {
				Error.error("Field \'" + fr.fieldName().getname() + "\' not found.");
			}
		}
		else{
			System.out.println("FieldRef:\t Target too complicated for now!");
		}

		super.visitFieldRef(fr);
		return null;
	}

	public Object visitClassType(ClassType ct) {
		println("ClassType:\t Looking up class/interface \'" + ct.typeName() + "\' in class table.");
		if (this.classTable.get(ct.typeName()) != null) {
			ct.myDecl = (ClassDecl)this.classTable.get(ct.typeName());
		}
		else{
			Error.error("Class \'"+ ct.typeName() + "\' not found.");
		}

		super.visitClassType(ct);

		return null;
	}

	public Object visitNameExpr(NameExpr ne) {

		println("NameExpr:\t Looking up symbol \'" + ne.name().getname() + "\'.");

		Object checkScopeHierarchy = currentScope.get(ne.name().getname());
		if (checkScopeHierarchy == null){
			checkScopeHierarchy = getField(ne.name().getname(), this.currentClass);
			if (checkScopeHierarchy == null) {
				checkScopeHierarchy = this.classTable.get(ne.name().getname());
			}
		}

		if (checkScopeHierarchy == null){
			Error.error("Symbol \'" + ne.name().getname() + "\' not declared.");
		}
		else{
			ne.myDecl = (AST)checkScopeHierarchy;

			if(checkScopeHierarchy instanceof ClassDecl){
				System.out.println(" Found Class");
			}
			else if(checkScopeHierarchy instanceof FieldDecl){
				System.out.println(" Found Field");
			}
			else if(checkScopeHierarchy instanceof ParamDecl){
				System.out.println(" Found Parameter");
			}
			else if(checkScopeHierarchy instanceof LocalDecl){
				System.out.println(" Found Local Variable");
			}

		}

		return null;
	}

	/** THIS */
	public Object visitThis(This th) {
		println("This:\t Visiting This.");
		ClassType ct = new ClassType(new Name(new Token(16,currentClass.name(),0,0,0)));
		ct.myDecl = currentClass;
		th.type = ct;
		return null;
	}

}

