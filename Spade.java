import java.io.File;
import java.io.FileNotFoundException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Scanner;

public class Spade {
	/* Columns 0-4 are the timestamp (year, month, day, hour, minute). */
	public static final int FIRST_SENSOR = 5;
	/* The last two columns are the activity label and the occupancy flag. */
	public static final int TRAILING_COLUMNS = 2;
	/* Sensor column i encodes to (char)(EVENT_BASE + i): column 5 -> 'A'. */
	private static final int EVENT_BASE = 60;
	/* Longest history considered when predicting the next event from the tree. */
	private static final int MAX_ORDER = 5;
	/*
	 * Backs off past any context seen fewer times than this, the usual guard against
	 * a depth-5 context that occurred once predicting its single successor with
	 * certainty. Left at 1 (off) because it measured as a null result here: sweeping
	 * 1 to 32 moved held-out top-1 by under 2 points, inside the noise of 385 scored
	 * events. Kept as a knob so the sweep can be repeated on other data.
	 */
	private static final int MIN_CONTEXT_COUNT = 1;
	/* Fraction of the sequence used to build the tree when scoring held out. */
	private static final double TRAIN_FRACTION = 0.8;

	public static void main(String[] args) throws FileNotFoundException {
		File myFile = new File(args.length > 0 ? args[0] : "canvas.csv");
		evaluate(encodeSequence(myFile));
	}

	/*
	 * Turns the sensor readings into an event sequence. A sensor switching on emits
	 * its uppercase symbol, switching off emits the lowercase one, so an episode is
	 * the span between a sensor turning on and turning back off.
	 */
	public static String encodeSequence(File csv) throws FileNotFoundException {
		StringBuilder sequence = new StringBuilder();

		try (Scanner scnr = new Scanner(csv)) {
			if (!scnr.hasNextLine())
				return "";

			String[] previous = scnr.nextLine().split(",");

			// The first row is the initial state: everything already active opens an episode.
			for (int i = FIRST_SENSOR; i < previous.length - TRAILING_COLUMNS; i++) {
				if (isActive(previous[i]))
					sequence.append(onEvent(i));
			}

			while (scnr.hasNextLine()) {
				String[] attribute = scnr.nextLine().split(",");

				for (int i = FIRST_SENSOR; i < attribute.length - TRAILING_COLUMNS; i++) {
					if (isActive(previous[i])) {
						if (isInactive(attribute[i]))
							sequence.append(offEvent(i));
					}
					else if (isInactive(previous[i])) {
						if (isActive(attribute[i]))
							sequence.append(onEvent(i));
					}
				}
				previous = attribute;
			}
		}
		return sequence.toString();
	}

	public static boolean isActive(String value) {
		return value.equals("ON") || value.equals("PRESENT")
				|| (!value.equals("0.0") && isDouble(value));
	}

	public static boolean isInactive(String value) {
		return value.equals("OFF") || value.equals("ABSENT") || value.equals("0.0");
	}

	/*
	 * Episodes rely on the on/off symbols being an upper/lowercase letter pair, so a
	 * column that falls outside A-Z is rejected rather than silently encoded to
	 * punctuation that getEpisodes would ignore.
	 */
	public static char onEvent(int column) {
		char c = (char) (EVENT_BASE + column);
		if (c < 'A' || c > 'Z')
			throw new IllegalArgumentException("sensor column " + column + " encodes to '" + c
					+ "', outside A-Z; the encoding supports at most 26 sensor columns starting at "
					+ FIRST_SENSOR);
		return c;
	}

	public static char offEvent(int column) {
		return Character.toLowerCase(onEvent(column));
	}

	public static void evaluate(String sequence) {
		Tree tree = new Tree();
		tree.genContext(getEpisodes(sequence));

		TreeNode[] arr = tree.root.children.values().toArray(new TreeNode[0]);
		sort(arr);

		// Original metric: how far into the probability-ranked list the actual event sits.
		int attmptCount = 0, correctAttmpt = 0;
		long totalRank = 0;
		int ranked = 0, top1 = 0;

		for (char event : sequence.toCharArray()) {
			int rank = rankOf(arr, event);
			if (rank == 0) {
				attmptCount += arr.length;
				continue;
			}
			attmptCount += rank;
			correctAttmpt++;
			totalRank += rank;
			ranked++;
			if (rank == 1)
				top1++;
		}

		System.out.println("Events: " + sequence.length() + " | contexts: " + tree.root.children.size()
				+ " | tree nodes: " + tree.size());
		if (ranked == 0) {
			System.out.println("No events to score.");
			return;
		}

		System.out.println("Scan efficiency: " + pct(correctAttmpt, attmptCount) + "%");
		System.out.println("Unigram top-1 accuracy: " + pct(top1, ranked)
				+ "% (mean rank " + String.format("%.2f", (double) totalRank / ranked)
				+ " of " + arr.length + ")");

		int contextTop1 = 0, contextRanked = 0;
		for (int t = 0; t < sequence.length(); t++) {
			TreeNode[] candidates = predict(tree, sequence, t);
			if (candidates.length == 0)
				continue;
			contextRanked++;
			if (candidates[0].event == sequence.charAt(t))
				contextTop1++;
		}
		// In-sample: the tree is built from the same sequence it is scored against,
		// so this measures fit, not generalisation. The unigram numbers above share
		// that caveat, making it a like-for-like comparison rather than a held-out one.
		System.out.println("Context top-1 accuracy (in-sample): " + pct(contextTop1, contextRanked)
				+ "% (order <= " + MAX_ORDER + ", " + contextRanked + " predictions)");

		evaluateHeldOut(sequence, TRAIN_FRACTION, MAX_ORDER, MIN_CONTEXT_COUNT);
	}

	/*
	 * Builds the tree from the first trainFraction of the sequence and scores the
	 * rest, so the accuracy covers events the tree has never seen. History fed to
	 * the predictor is the real past, which a model would have at prediction time,
	 * but no episode from the scored region contributes a count.
	 */
	public static double evaluateHeldOut(String sequence, double trainFraction, int maxOrder, int minCount) {
		int split = (int) (sequence.length() * trainFraction);
		if (split < 2 || split >= sequence.length())
			return 0.0;

		Tree tree = new Tree();
		tree.genContext(getEpisodes(sequence.substring(0, split)));
		if (tree.root.children.isEmpty())
			return 0.0;

		TreeNode[] unigram = tree.root.children.values().toArray(new TreeNode[0]);
		sort(unigram);

		int scored = 0, contextHits = 0, unigramHits = 0;
		for (int t = split; t < sequence.length(); t++) {
			scored++;
			if (unigram[0].event == sequence.charAt(t))
				unigramHits++;

			TreeNode[] candidates = predict(tree, sequence, t, maxOrder, minCount);
			if (candidates.length > 0 && candidates[0].event == sequence.charAt(t))
				contextHits++;
		}

		System.out.println("Held-out top-1 accuracy: " + pct(contextHits, scored)
				+ "% context vs " + pct(unigramHits, scored) + "% unigram baseline"
				+ " (trained on " + split + ", scored on " + scored
				+ ", order <= " + maxOrder + ", min context count " + minCount + ")");
		return (double) contextHits / scored;
	}

	/*
	 * Ranks the candidates for sequence[t] using the longest history the tree has
	 * actually seen, backing off toward the root when a longer context is unknown.
	 */
	public static TreeNode[] predict(Tree tree, String sequence, int t) {
		return predict(tree, sequence, t, MAX_ORDER, MIN_CONTEXT_COUNT);
	}

	public static TreeNode[] predict(Tree tree, String sequence, int t, int maxOrder, int minCount) {
		TreeNode context = tree.root;

		for (int order = Math.min(maxOrder, t); order >= 1; order--) {
			TreeNode node = tree.root;
			for (int i = t - order; i < t && node != null; i++)
				node = node.children.get(sequence.charAt(i));

			if (node != null && node.frequency >= minCount && !node.children.isEmpty()) {
				context = node;
				break;
			}
		}

		TreeNode[] candidates = context.children.values().toArray(new TreeNode[0]);
		sort(candidates);
		return candidates;
	}

	/* 1-based position of the event in the ranked array, or 0 if it is absent. */
	public static int rankOf(TreeNode[] nodes, char event) {
		for (int i = 0; i < nodes.length; i++) {
			if (nodes[i].event == event)
				return i + 1;
		}
		return 0;
	}

	/* Most probable first -- callers scan from index 0 and stop at the first match. */
	public static void sort(TreeNode[] nodes) {
		Arrays.sort(nodes, (a, b) -> Double.compare(b.probability, a.probability));
	}

	private static double pct(int n, int total) {
		return total == 0 ? 0.0 : (double) n / total * 100;
	}

	public static ArrayList<String> getEpisodes(String sequence) {
		ArrayList<String> arr = new ArrayList<String>();
		for (int i = 0; i < sequence.length(); i++) {
			char c = sequence.charAt(i);
			if (Character.isUpperCase(c)) {
				String seq = sequence.substring(i);
				int charLoc = seq.indexOf(Character.toLowerCase(c));
				if (charLoc != -1) {
					arr.add(sequence.substring(i, charLoc + i + 1));
				}
			}
		}
		return arr;
	}

	public static boolean isDouble(String str) {
		try {
			Double.parseDouble(str);
			return true;
		}
		catch (NumberFormatException ex) {return false;}
	}
}
