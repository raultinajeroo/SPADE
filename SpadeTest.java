import java.io.File;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;

/*
 * Zero-dependency test suite. Run with:
 *   javac *.java && java SpadeTest
 * Exits non-zero if anything fails.
 */
public class SpadeTest {
	private static int passed = 0;
	private static final ArrayList<String> failures = new ArrayList<String>();

	public static void main(String[] args) throws Exception {
		encodingTests();
		episodeTests();
		treeTests();
		probabilityTests();
		sortTests();
		predictionTests();
		heldOutTests();
		canvasRegressionTests();

		System.out.println();
		System.out.println(passed + " passed, " + failures.size() + " failed");
		for (String f : failures)
			System.out.println("  FAIL " + f);
		System.exit(failures.isEmpty() ? 0 : 1);
	}

	/* ---------------- sensor encoding ---------------- */

	private static void encodingTests() throws Exception {
		eq("onEvent(5) is 'A'", 'A', Spade.onEvent(5));
		eq("offEvent(5) is 'a'", 'a', Spade.offEvent(5));
		eq("onEvent(27) is 'W'", 'W', Spade.onEvent(27));
		eq("onEvent(30) is 'Z'", 'Z', Spade.onEvent(30));
		check("onEvent(31) is rejected", throwsIllegalArgument(31));
		check("onEvent(4) is rejected", throwsIllegalArgument(4));

		check("active values", Spade.isActive("ON") && Spade.isActive("PRESENT") && Spade.isActive("2.5"));
		check("inactive values", Spade.isInactive("OFF") && Spade.isInactive("ABSENT") && Spade.isInactive("0.0"));
		check("0.0 is not active", !Spade.isActive("0.0"));
		check("label is neither", !Spade.isActive("Phone_Call") && !Spade.isInactive("Phone_Call"));

		// Two sensors: row 1 seeds state, then sensor 5 turns on, then 5 off and 6 on.
		File csv = csv(
				"2008,1,1,0,0,OFF,OFF,Cook,PRESENT",
				"2008,1,1,0,1,ON,OFF,Cook,PRESENT",
				"2008,1,1,0,2,OFF,ON,Cook,PRESENT");
		eq("encodeSequence transitions", "AaB", Spade.encodeSequence(csv));

		File seeded = csv(
				"2008,1,1,0,0,ON,PRESENT,Cook,PRESENT",
				"2008,1,1,0,1,OFF,PRESENT,Cook,PRESENT");
		eq("encodeSequence seeds initial state", "ABa", Spade.encodeSequence(seeded));

		eq("empty file yields empty sequence", "", Spade.encodeSequence(csv()));

		File wide = csv(row(5 + 27 + 2));
		check("too many sensor columns is rejected", throwsIllegalArgument(wide));
	}

	/* ---------------- episode extraction ---------------- */

	private static void episodeTests() {
		eq("disjoint episodes", "[Aa, Bb]", Spade.getEpisodes("AaBb").toString());
		eq("interleaved episodes", "[ABa, Bab]", Spade.getEpisodes("ABab").toString());
		eq("unclosed episode dropped", "[]", Spade.getEpisodes("AB").toString());
		eq("mismatched close dropped", "[]", Spade.getEpisodes("Ab").toString());
		eq("empty sequence", "[]", Spade.getEpisodes("").toString());
		eq("closes on first match", "[Aa]", Spade.getEpisodes("Aaa").toString());
	}

	/* ---------------- tree construction ---------------- */

	private static void treeTests() {
		Tree tree = new Tree();
		tree.genContext(episodes("Aa"));

		eq("root has both events", 2, tree.root.children.size());
		eq("root frequency sums children", 2, tree.root.frequency);
		eq("A seen once", 1, tree.root.children.get('A').frequency);
		eq("a follows A", 1, tree.root.children.get('A').children.get('a').frequency);
		eq("tree size", 3, tree.size());

		// "Aa" twice: every count doubles, structure unchanged.
		Tree repeated = new Tree();
		repeated.genContext(episodes("Aa", "Aa"));
		eq("repeat keeps structure", 2, repeated.root.children.size());
		eq("repeat doubles frequency", 2, repeated.root.children.get('A').frequency);
		eq("repeat doubles root frequency", 4, repeated.root.frequency);

		// Suffixes are inserted, so "ABa" registers B as a root context too.
		Tree suffix = new Tree();
		suffix.genContext(episodes("ABa"));
		check("suffix context exists", suffix.root.children.containsKey('B'));
		check("B -> a recorded", suffix.root.children.get('B').children.containsKey('a'));
		check("A -> B -> a recorded",
				suffix.root.children.get('A').children.get('B').children.containsKey('a'));
	}

	/* ---------------- probabilities ---------------- */

	private static void probabilityTests() {
		Tree tree = new Tree();
		tree.genContext(episodes("Aa"));
		eq("P(A) at root", 0.5, tree.root.children.get('A').probability);
		eq("P(a|A)", 1.0, tree.root.children.get('A').children.get('a').probability);

		// Regression: node A branches to both 'a' and 'B'. The old single-child
		// recursion assigned a probability to only one of them.
		Tree branching = new Tree();
		branching.genContext(Spade.getEpisodes("AaABa"));
		TreeNode a = branching.root.children.get('A');
		eq("branching node has two children", 2, a.children.size());
		check("branch 'a' has a probability", a.children.get('a').probability > 0.0);
		check("branch 'B' has a probability", a.children.get('B').probability > 0.0);
		check("no zero probabilities anywhere", countZeroProbabilities(branching) == 0);

		double rootSum = 0.0;
		for (TreeNode child : branching.root.children.values())
			rootSum += child.probability;
		check("root probabilities sum to 1", Math.abs(rootSum - 1.0) < 1e-9);

		// A child can never be more likely than the context it hangs off.
		check("conditional probabilities are <= 1", maxProbability(branching) <= 1.0 + 1e-9);
	}

	/* ---------------- ranking ---------------- */

	private static void sortTests() {
		TreeNode low = new TreeNode('x');
		TreeNode mid = new TreeNode('y');
		TreeNode high = new TreeNode('z');
		low.probability = 0.1;
		mid.probability = 0.5;
		high.probability = 0.9;

		TreeNode[] nodes = {low, high, mid};
		Spade.sort(nodes);
		eq("most probable first", "zyx", "" + nodes[0].event + nodes[1].event + nodes[2].event);

		eq("rank of best is 1", 1, Spade.rankOf(nodes, 'z'));
		eq("rank of worst is 3", 3, Spade.rankOf(nodes, 'x'));
		eq("absent event ranks 0", 0, Spade.rankOf(nodes, 'q'));
	}

	/* ---------------- context prediction ---------------- */

	private static void predictionTests() {
		// 'A' is always followed by 'B', so the order-1 context must rank B first.
		Tree tree = new Tree();
		tree.genContext(Spade.getEpisodes("ABaABaABa"));

		TreeNode[] candidates = Spade.predict(tree, "ABaABaABa", 1, 5, 1);
		check("prediction returns candidates", candidates.length > 0);
		eq("A predicts B", 'B', candidates[0].event);

		// At t=0 there is no history, so it backs off to the root contexts.
		TreeNode[] atStart = Spade.predict(tree, "ABaABaABa", 0, 5, 1);
		eq("cold start falls back to root", tree.root.children.size(), atStart.length);

		// An unseen context also backs off rather than returning nothing.
		TreeNode[] unseen = Spade.predict(tree, "ZZZ", 2, 5, 1);
		eq("unknown context falls back to root", tree.root.children.size(), unseen.length);

		// A context rarer than minCount is skipped, so a high threshold backs off
		// all the way to the root contexts.
		TreeNode[] thresholded = Spade.predict(tree, "ABaABaABa", 1, 5, 9999);
		eq("min count forces backoff", tree.root.children.size(), thresholded.length);

		// order 0 disables context entirely and leaves the root ranking.
		TreeNode[] orderZero = Spade.predict(tree, "ABaABaABa", 1, 0, 1);
		eq("order 0 falls back to root", tree.root.children.size(), orderZero.length);
	}

	/* ---------------- held-out evaluation ---------------- */

	private static void heldOutTests() throws Exception {
		File canvas = new File("canvas.csv");
		if (!canvas.exists())
			return;

		String sequence = Spade.encodeSequence(canvas);
		double acc = Spade.evaluateHeldOut(sequence, 0.8, 5, 1);
		check("held-out accuracy is a proportion", acc > 0.0 && acc <= 1.0);
		check("held-out beats the unigram baseline", acc > 0.30);

		// Degenerate splits must not throw or divide by zero.
		eq("split past the end scores nothing", 0.0, Spade.evaluateHeldOut(sequence, 1.0, 5, 1));
		eq("empty sequence scores nothing", 0.0, Spade.evaluateHeldOut("", 0.8, 5, 1));
		eq("tiny sequence scores nothing", 0.0, Spade.evaluateHeldOut("Aa", 0.8, 5, 1));
	}

	/* ---------------- end-to-end on the shipped dataset ---------------- */

	private static void canvasRegressionTests() throws Exception {
		File canvas = new File("canvas.csv");
		if (!canvas.exists()) {
			failures.add("canvas.csv missing - end-to-end checks skipped");
			return;
		}

		String sequence = Spade.encodeSequence(canvas);
		eq("canvas sequence length", 1922, sequence.length());
		eq("canvas distinct symbols", 46L, sequence.chars().distinct().count());

		ArrayList<String> eps = Spade.getEpisodes(sequence);
		eq("canvas episode count", 956, eps.size());

		Tree tree = new Tree();
		tree.genContext(eps);
		eq("canvas root contexts", 46, tree.root.children.size());
		eq("canvas root frequency", 21240, tree.root.frequency);
		eq("canvas tree size", 122784, tree.size());
		eq("canvas nodes without a probability", 0, countZeroProbabilities(tree));
	}

	/* ---------------- helpers ---------------- */

	private static ArrayList<String> episodes(String... eps) {
		return new ArrayList<String>(Arrays.asList(eps));
	}

	private static int countZeroProbabilities(Tree tree) {
		int zero = 0;
		for (TreeNode child : tree.root.children.values())
			zero += countZeroRec(child);
		return zero;
	}

	private static int countZeroRec(TreeNode node) {
		int zero = node.probability <= 0.0 ? 1 : 0;
		for (TreeNode child : node.children.values())
			zero += countZeroRec(child);
		return zero;
	}

	private static double maxProbability(Tree tree) {
		double max = 0.0;
		for (TreeNode child : tree.root.children.values())
			max = Math.max(max, maxProbabilityRec(child));
		return max;
	}

	private static double maxProbabilityRec(TreeNode node) {
		double max = node.probability;
		for (TreeNode child : node.children.values())
			max = Math.max(max, maxProbabilityRec(child));
		return max;
	}

	private static String row(int columns) {
		StringBuilder sb = new StringBuilder("2008,1,1,0,0");
		for (int i = 5; i < columns; i++)
			sb.append(",ON");
		return sb.toString();
	}

	private static File csv(String... lines) throws Exception {
		File dir = Files.createTempDirectory("spade-test").toFile();
		dir.deleteOnExit();
		File file = new File(dir, "input.csv");
		file.deleteOnExit();
		try (PrintWriter out = new PrintWriter(file)) {
			for (String line : lines)
				out.println(line);
		}
		return file;
	}

	private static boolean throwsIllegalArgument(int column) {
		try {
			Spade.onEvent(column);
			return false;
		}
		catch (IllegalArgumentException ex) {return true;}
	}

	private static boolean throwsIllegalArgument(File csv) {
		try {
			Spade.encodeSequence(csv);
			return false;
		}
		catch (IllegalArgumentException ex) {return true;}
		catch (Exception ex) {return false;}
	}

	private static void check(String name, boolean condition) {
		if (condition) {passed++;}
		else {failures.add(name);}
	}

	private static void eq(String name, Object expected, Object actual) {
		if (expected.equals(actual)) {passed++;}
		else {failures.add(name + " -- expected <" + expected + "> but was <" + actual + ">");}
	}
}
