import java.util.ArrayList;
import java.util.List;

public class Tree {
	public TreeNode root;

	public Tree() {
		this.root = new TreeNode();
	}

	/*
	 * Builds the context tree. For every episode, each event advances all of the
	 * contexts opened so far and opens a new one at the root, so the tree ends up
	 * holding every suffix of every episode. A path root -> x -> y therefore means
	 * "y was observed after x", and a node's frequency is how often that context
	 * occurred.
	 */
	public void genContext(ArrayList<String> Ep) {
		for (String str : Ep) {
			List<TreeNode> tracker = new ArrayList<TreeNode>();

			for (char event : str.toCharArray()) {
				List<TreeNode> advanced = new ArrayList<TreeNode>(tracker.size() + 1);

				for (TreeNode node : tracker)
					advanced.add(visit(node, event));
				advanced.add(visit(this.root, event));

				tracker = advanced;
			}
		}
		this.setRootFrequency();
		this.setProbabilities();
	}

	/* Steps to the child for this event, creating it on first sight, and counts the visit. */
	private TreeNode visit(TreeNode node, char event) {
		TreeNode child = node.children.get(event);
		if (child == null) {
			child = new TreeNode(event);
			node.children.put(event, child);
		}
		else {
			child.frequency++;
		}
		return child;
	}

	public void setProbabilities() {
		for (TreeNode child : this.root.children.values())
			setProbabilitiesRec(child, (double) this.root.frequency);
	}

	/* P(node | its parent context) -- assigned for every node, on every branch. */
	private void setProbabilitiesRec(TreeNode node, double parentFrequency) {
		node.probability = node.frequency / parentFrequency;
		for (TreeNode child : node.children.values())
			setProbabilitiesRec(child, (double) node.frequency);
	}

	public void setRootFrequency() {
		this.root.frequency = 0;
		for (TreeNode child : this.root.children.values())
			this.root.frequency += child.frequency;
	}

	public int size() {
		int total = 0;
		for (TreeNode child : this.root.children.values())
			total += sizeRec(child);
		return total;
	}

	private int sizeRec(TreeNode node) {
		int total = 1;
		for (TreeNode child : node.children.values())
			total += sizeRec(child);
		return total;
	}

	public void printTree() {
		printTree(Integer.MAX_VALUE);
	}

	/* Prints every context in the tree, one path per line, down to maxDepth. */
	public void printTree(int maxDepth) {
		for (TreeNode child : this.root.children.values())
			printTreeRec(child, "", maxDepth);
	}

	private void printTreeRec(TreeNode node, String prefix, int maxDepth) {
		String path = prefix + node.event;
		if (path.length() > maxDepth)
			return;
		System.out.println(path + " (freq=" + node.frequency
				+ ", p=" + String.format("%.4f", node.probability) + ")");
		for (TreeNode child : node.children.values())
			printTreeRec(child, path, maxDepth);
	}
}
