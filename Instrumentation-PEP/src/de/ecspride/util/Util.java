package de.ecspride.util;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.xmlpull.v1.XmlPullParserException;

import soot.Body;
import soot.Local;
import soot.Printer;
import soot.RefType;
import soot.Scene;
import soot.SootClass;
import soot.SootMethod;
import soot.Type;
import soot.Unit;
import soot.javaToJimple.LocalGenerator;
import soot.jimple.InvokeStmt;
import soot.jimple.Jimple;
import soot.jimple.SpecialInvokeExpr;
import soot.jimple.StaticInvokeExpr;
import soot.jimple.infoflow.android.manifest.ProcessManifest;
import soot.util.EscapedWriter;
import de.ecspride.Settings;
import de.ecspride.instrumentation.Instrumentation;

/**
 * Different util methods which do not fit into the transformer class (PolicyEnforcementTransformer).
 * @author Siegfried Rasthofer
 *
 */
public class Util {
	public static boolean isAndroidClass(SootClass c){
		return c.getName().startsWith("android.");
	}
	
	public static void clearSootOutputJimpleDir(){
		System.err.println("deleting sootOutput folder...");
		cleanFolder("./sootOutput");	
	}
	
	private static void cleanFolder(String fileName) {
		File outputDir = new File(fileName);
		if (!outputDir.exists())
			return;
		
		for (String fileInDir : outputDir.list()) {
			File f = new File(outputDir.getAbsolutePath()
					+ File.separator + fileInDir);
			if (f.isDirectory())
				cleanFolder(f.getAbsolutePath());
			f.delete();
		}
	}

	public static void writeJimpleFiles(SootClass c){
		String correctFormat = c.getPackageName().replace(".", File.separator);
		File dir = new File("sootOutput" + File.separator + "/jimple" + File.separator + correctFormat);
		dir.mkdirs();
		
		String fileName = c.getName().substring(c.getName().lastIndexOf(".") + 1);
		
		
		try{			
			PrintWriter pw = new PrintWriter(
                    new EscapedWriter(new OutputStreamWriter(
                    		new FileOutputStream(dir.getAbsolutePath() + "/" + fileName + ".jimple")))); 
			
			Printer.v().printTo(c, pw);
			pw.close();
		} catch (IOException e) {
			e.printStackTrace();
		} catch (Exception e){
			e.printStackTrace();
		}
	}
	
	/**
	 * This is a kind of hack and includes much more instrumentation which is necessary;
	 * But it is still sound ;-).
	 * @param apkFileLocation: apk file path
	 */
	public static void initializePePInAllPossibleClasses(String apkFileLocation){
		try {
			ProcessManifest manifest = new ProcessManifest(apkFileLocation);		
			Set<String> entryClasses = manifest.getEntryPointClasses();
			 
			for(String entryClass : entryClasses){
				SootClass sc = Scene.v().getSootClass(entryClass);
				
				List<SootClass> allSuperClasses = Scene.v().getActiveHierarchy().getSuperclassesOf(sc);
			 	for(SootClass subclass : allSuperClasses)
			 		if(subclass.getName().equals("android.content.Context")){
			 			initializePeP(sc);
			 			break;
			 		}
			}
		}
		catch (IOException | XmlPullParserException ex) {
			System.err.println("Could not read Android manifest file: " + ex.getMessage());
			throw new RuntimeException(ex);
		}
	}
	
	private static void initializePeP(SootClass sc){
		SootMethod onCreate = null;
		for(SootMethod sm : sc.getMethods()){
			if(sm.getName().equals("onCreate") && 
					sm.getParameterCount() == 1 && 
					sm.getParameterType(0).toString().equals("android.os.Bundle")){
				onCreate = sm;
			}
		}
	
		if(onCreate != null){
			List<Unit> generated = new ArrayList<Unit>();
			Body body = onCreate.retrieveActiveBody();
			
			Local thisLocal = body.getThisLocal();
			SootClass context = Scene.v().forceResolve("android.content.Context", SootClass.BODIES);
//				SootMethod applicationContext =sc.getMethod("android.content.Context getApplicationContext()");
			SootMethod applicationContext = context.getMethod("android.content.Context getApplicationContext()");
			SpecialInvokeExpr virtInvExpr = Jimple.v().newSpecialInvokeExpr(thisLocal, applicationContext.makeRef());
			
			Local applicationContextLocal = generateFreshLocal(body, RefType.v("android.content.Context"));
			generated.add(Jimple.v().newAssignStmt(applicationContextLocal, virtInvExpr));
			
			Object[] typeAndArgument = new Object[2];
			typeAndArgument[0] = RefType.v("android.content.Context");
			typeAndArgument[1] = applicationContextLocal;
			
			StaticInvokeExpr staticInvExpr = Instrumentation.createJimpleStaticInvokeExpr(Settings.instance.INSTRUMENTATION_HELPER_JAVA, Settings.instance.INSTRUMENTATION_HELPER_INITIALIZE_METHOD, typeAndArgument);
			generated.add(Jimple.v().newInvokeStmt(staticInvExpr));
			
			Unit onCreateSpecialInvoke = getUnitAfterSuperOnCreate(body);
			if(onCreateSpecialInvoke == null)
				throw new RuntimeException("Oops, something went all wonky");
			
			body.getUnits().insertAfter(generated, onCreateSpecialInvoke);
		}
		
		
	}
	
	private static Local generateFreshLocal(Body b, Type type){
		LocalGenerator lg = new LocalGenerator(b);
		return lg.generateLocal(type);
	}
	
	private static Unit getUnitAfterSuperOnCreate(Body b) {
		for(Unit u : b.getUnits()){
			if(u instanceof InvokeStmt){
				InvokeStmt invStmt = (InvokeStmt)u;
				if(invStmt.getInvokeExpr().getMethod().getSubSignature().equals("void onCreate(android.os.Bundle)"))
					return u;
			}
		}
		
		return null;
	}
}
