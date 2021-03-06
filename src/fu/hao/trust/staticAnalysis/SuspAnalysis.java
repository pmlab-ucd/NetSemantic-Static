package fu.hao.trust.staticAnalysis;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;

import com.opencsv.CSVWriter;

import fu.hao.trust.data.App;
import fu.hao.trust.data.Results;
import fu.hao.trust.utils.Log;
import fu.hao.trust.utils.Settings;
import soot.Scene;
import soot.SootClass;
import soot.SootMethod;
import soot.Unit;
import soot.jimple.IfStmt;
import soot.jimple.Stmt;
import soot.jimple.infoflow.source.data.SourceSinkDefinition;
import soot.jimple.internal.ConditionExprBox;
import soot.jimple.toolkits.callgraph.CallGraph;
import soot.jimple.toolkits.callgraph.Edge;
import soot.jimple.toolkits.ide.icfg.JimpleBasedInterproceduralCFG;

/**
 * @ClassName: SuspAnalysisCG
 * @Description: BFS Call Graph
 * @author: Hao Fu
 * @date: Feb 25, 2016 10:38:04 AM
 */
public class SuspAnalysis {
	private final String TAG = getClass().toString();

	private static CallGraph cg;
	private static JimpleBasedInterproceduralCFG icfg;

	private static App app;

	private static Set<Unit> srcMethods;
	private static Set<Unit> sinkMethods;
	private static Set<SootMethod> suspicous;
	// private static Map<Unit, Map<Unit, UnitNode>> sensitives;
	Map<SootClass, List<SootMethod>> res = new HashMap<>();

	// The nested class to implement singleton
	private static class SingletonHolder {
		private static final SuspAnalysis instance = new SuspAnalysis();
	}

	// Get THE instance
	public static final SuspAnalysis v() {
		return SingletonHolder.instance;
	}

	class UnitNode {
		IfStmt stmt;
		Unit prev;
		Unit next;

		boolean forward = true;

		UnitNode(IfStmt stmt, Unit prev, Unit next) {
			this.stmt = stmt;
			this.prev = prev;
			this.next = next;
			Log.warn(TAG, "stmt " + stmt + " " + stmt.getTarget());
			if (stmt.getTarget().equals(prev)) {
				forward = false;
			}
			ConditionExprBox cond = (ConditionExprBox) stmt.getConditionBox();
			Log.warn(TAG, "" + cond + " size "
					+ cond.getValue().getUseBoxes().size());
			if (prev != null) {
				Log.warn(TAG, "prev " + prev);
				Log.warn(TAG, "next " + next);
				Log.warn(TAG, "forward: " + forward);
			}
		}

	}

	public Set<SootMethod> runAnalysis() throws IOException {
		runAnalysisOld();
		SootClass application = null;
		boolean appSink = false;

		SootMethod dummyMain = Scene.v().getMethod(
				"<dummyMainClass: void dummyMainMethod(java.lang.String[])>");

		// Run twice to add potential missed.
		for (int j = 0; j < 2; j++) {
			Set<SootMethod> visited = new HashSet<>();
			Log.warn(TAG, "j " + j);
			for (Unit mainUnit : icfg.getOrCreateUnitGraph(dummyMain)) {
				Stmt stmt = (Stmt) mainUnit;
				if (stmt.containsInvokeExpr()) {
					SootMethod topMethod = stmt.getInvokeExpr().getMethod();

					if (topMethod.getDeclaringClass().getName()
							.startsWith("android")
							|| topMethod.getDeclaringClass().getName()
									.startsWith("java")
							|| topMethod.getDeclaringClass().getName()
									.startsWith("org.apache.http")) {
						continue;
					}

					Queue<SootMethod> queue = new LinkedList<>();
					queue.add(topMethod);

					Log.warn(TAG, "top :: " + topMethod);

					while (!queue.isEmpty()) {
						int len = queue.size();
						for (int i = 0; i < len; i++) {
							SootMethod node = queue.poll();
							if (visited.contains(node)) {
								continue;
							}
							visited.add(node);
							Iterator<Edge> iterator = cg.edgesOutOf(node);
							while (iterator.hasNext()) {
								Edge out = iterator.next();
								Unit unit = out.srcUnit();
								queue.add(out.getTgt().method());

								if (unit == null) {
									continue;
								}

								if (srcMethods.contains(unit)) {
									// sensitives.put(unit, getPrevNodes(unit));
									Log.warn(TAG, "Top src found " + unit
											+ " from " + topMethod);
									SootClass topClass = topMethod
											.getDeclaringClass();
									while (topClass.hasSuperclass()) {
										if (topClass.toString().equals(
												"android.app.Activity")
												|| topClass
														.toString()
														.equals("android.content.ContentProvider")
												|| topClass
														.toString()
														.equals("android.content.BroadcastReceiver")
												|| topClass.toString().equals(
														"android.app.Service")) {
											break;
										} else if (topClass.toString().equals(
												"android.app.Application")) {
											application = topClass;
											break;
										} else if (topClass.toString()
												.contains("onClick")) {
											topClass = Scene.v().getSootClass(
													"android.app.Activity");
											break;
										}
										topClass = topClass.getSuperclass();
										Log.warn(TAG, topClass.toString());
									}

									if (topMethod.toString()
											.contains("onClick")) {
										topClass = Scene.v().getSootClass(
												"android.app.Activity");
									}

									if (!res.containsKey(topClass)) {
										List<SootMethod> chain = new LinkedList<>();
										chain.add(topMethod);
										// Dummy node for each
										// component(activity, service and icc)

										res.put(topClass, chain);
									}

									if (!res.get(topClass).contains(topMethod)) {
										res.get(topClass).add(topMethod);
									}

									if (appSink) {
										if (!res.containsKey(application)) {
											List<SootMethod> chain = new LinkedList<>();
											chain.add(topMethod);
											// Dummy node for each
											// component(activity, service and
											// icc)

											res.put(application, chain);
										}
										res.get(application).add(topMethod);
									}

									queue.clear();
								}

								if (sinkMethods.contains(unit)) {
									Log.warn(TAG, "Top sink found " + unit
											+ " from " + topMethod);
									SootClass topClass = topMethod
											.getDeclaringClass();
									while (topClass.hasSuperclass()) {
										if (topClass.toString().equals(
												"android.app.Activity")
												|| topClass
														.toString()
														.equals("android.content.ContentProvider")
												|| topClass
														.toString()
														.equals("android.content.BroadcastReceiver")
												|| topClass.toString().equals(
														"android.app.Service")) {
											break;
										} else if (topClass.toString().equals(
												"android.app.Application")) {
											application = topClass;
											appSink = true;
											Log.warn(TAG, "app sink on!");
											break;
										}
										topClass = topClass.getSuperclass();
									}

									if (topMethod.toString()
											.contains("onClick")) {
										topClass = Scene.v().getSootClass(
												"android.app.Activity");
									}

									// sensitives.put(unit, getPrevNodes(unit));
									if (res.containsKey(topClass)) {
										Log.warn(TAG, "Top sink found2 " + unit
												+ " from " + topMethod + " "
												+ topClass);
										if (!res.get(topClass).contains(
												topMethod)) {
											res.get(topClass).add(topMethod);
										}
										queue.clear();
									}

									if (application != null
											&& res.containsKey(application)) {
										Log.warn(TAG, "Top sink found2 " + unit
												+ " from " + topMethod + " "
												+ topClass);
										if (!res.get(application).contains(
												topMethod)) {
											res.get(application).add(topMethod);
										}
										queue.clear();
									}
								}

							}
						}
					}
				}
			}
		}

		writeCSV(res);

		return suspicous;
	}

	public Set<SootMethod> runAnalysisOld() throws IOException {
		app = App.v();
		cg = app.getCG();
		icfg = app.getICFG();
		srcMethods = new HashSet<>();
		sinkMethods = new HashSet<>();
		suspicous = new HashSet<>();
		// sensitives = new HashMap<>();

		for (SootClass sootClass : Scene.v().getClasses()) {
			for (SootMethod method : sootClass.getMethods()) {

				if (method.getSignature().contains("dummyMainMethod")) {
					continue;
				}

				try {
					for (Unit unit : icfg.getCallsFromWithin(method)) {
						for (SourceSinkDefinition srcDef : app.getApp()
								.getSources()) {
							if (unit.toString().contains((srcDef.toString()))) {
								srcMethods.add(unit);
								// sensitives.put(unit, getPrevNodes(unit));
								break;
							}
						}

						for (SourceSinkDefinition sinkDef : app.getApp()
								.getSinks()) {
							if (unit.toString().contains((sinkDef.toString()))) {
								sinkMethods.add(unit);
								// sensitives.put(unit, getPrevNodes(unit));
								break;
							}
						}
					}

					if (isSuspicous(method)) {
						suspicous.add(method);
						Log.warn(TAG, "found a suspicous method: " + method);
						Results.results.add(method.toString());
					}
				} catch (java.lang.ClassCastException e) {
					e.printStackTrace();
				} catch (java.lang.RuntimeException e) {
					Log.debug(TAG, e.getMessage());
				}

			}
		}

		return suspicous;
	}

	public Map<Unit, UnitNode> getPrevNodes(Unit sens) {
		Map<Unit, UnitNode> res = new HashMap<>();
		Queue<Unit> queue = new LinkedList<>();
		Set<Unit> visited = new HashSet<>();
		queue.add(sens);

		while (!queue.isEmpty()) {
			int len = queue.size();
			for (int i = 0; i < len; i++) {
				Unit unit = queue.poll();
				if (visited.contains(unit)) {
					continue;
				}
				visited.add(unit);

				for (Unit prev : icfg.getPredsOf(unit)) {
					queue.add(prev);
					if ((Stmt) prev instanceof IfStmt) {
						if (!res.containsKey(prev)) {
							// res.get(prev).setForward((IfStmt)prev, unit);
							// } else {
							res.put(prev, new UnitNode((IfStmt) prev, unit,
									icfg.getPredsOf(prev).get(0)));
						}
						Log.warn(TAG, sens + ", unit: " + prev);
					}
					Log.warn(TAG, "s " + icfg.getPredsOf(unit).size() + " "
							+ sens + ", unit: " + prev);
					Log.warn(TAG, "s " + icfg.getPredsOf(prev).size() + " "
							+ sens + ", unit: " + prev);
				}
			}
		}

		return res;
	}

	public boolean isSuspicous(SootMethod target) {
		Queue<SootMethod> queue = new LinkedList<>();
		Set<SootMethod> visited = new HashSet<>();
		queue.add(target);
		boolean srcFound = false;
		boolean sinkFound = false;
		while (!queue.isEmpty()) {
			int len = queue.size();
			for (int i = 0; i < len; i++) {
				SootMethod node = queue.poll();
				if (visited.contains(node)) {
					continue;
				}
				visited.add(node);
				Iterator<Edge> iterator = cg.edgesOutOf(node);
				while (iterator.hasNext()) {
					Edge out = iterator.next();

					if (out.getTgt().method().toString()
							.contains("android.support.v")) {
						break;
					}

					Unit unit = out.srcUnit();
					if (srcMethods.contains(unit)) {
						Log.warn(TAG, "src found " + unit);
						srcFound = true;
					}

					if (sinkMethods.contains(unit)) {
						Log.warn(TAG, "sink found " + unit);
						sinkFound = true;
					}

					if (srcFound && sinkFound) {
						return true;
					}
					queue.add(out.getTgt().method());
				}
			}
		}

		return false;
	}

	public Map<SootClass, List<SootMethod>> checkDummyMain(SootMethod target) {
		Queue<SootMethod> queue = new LinkedList<>();
		Set<SootMethod> visited = new HashSet<>();
		queue.add(target);
		Map<SootClass, List<SootMethod>> res = new HashMap<>();

		while (!queue.isEmpty()) {
			int len = queue.size();
			for (int i = 0; i < len; i++) {
				SootMethod node = queue.poll();
				if (visited.contains(node)) {
					continue;
				}
				visited.add(node);
				Iterator<Edge> iterator = cg.edgesOutOf(node);
				while (iterator.hasNext()) {
					Edge out = iterator.next();

					if (out.getTgt().method().toString()
							.contains("android.support.v")) {
						break;
					}

					Unit unit = out.srcUnit();
					if (srcMethods.contains(unit)) {
						Log.debug(TAG, "src found " + unit);
						if (!res.containsKey(node.getDeclaringClass())) {
							List<SootMethod> chain = new LinkedList<>();
							res.put(node.getDeclaringClass(), chain);
						}
						res.get(node.getDeclaringClass()).add(node);
					}

					if (sinkMethods.contains(unit)) {
						if (res.containsKey(node.getDeclaringClass())) {
							res.get(node.getDeclaringClass()).add(node);
						}
					}

					queue.add(out.getTgt().method());
				}
			}
		}

		Log.warn(TAG, "dummyMain: " + res);

		return res;
	}

	public static void writeCSV(Map<SootClass, List<SootMethod>> suspicous)
			throws IOException {
		String csv = "./sootOutput/" + Settings.apkName + "_dummy.csv";
		File csvFile = new File(csv);
		if (!csvFile.exists()) {
			csvFile.createNewFile();
		} else {
			csvFile.delete();
			csvFile.createNewFile();
		}
		CSVWriter writer = new CSVWriter(new FileWriter(csv, true));
		List<String[]> results = new ArrayList<>();

		for (SootClass sootClass : suspicous.keySet()) {
			List<String> result = new ArrayList<>();
			result.add(sootClass.toString());
			for (SootMethod method : suspicous.get(sootClass)) {
				// result.add(apkName);
				result.add(method.getDeclaringClass() + ":" + method.getName());
			}
			String[] resultArray = (String[]) result.toArray(new String[result
					.size()]);
			results.add(resultArray);
		}

		writer.writeAll(results);
		writer.close();
	}

}
