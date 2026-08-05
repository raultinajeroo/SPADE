import java.util.HashMap;

public class TreeNode {
	public char event;
	public int frequency;
	public double probability;
	public HashMap<Character, TreeNode> children;

	public TreeNode() {
		event = '*';
		frequency = 0;
		probability = 0.0;
		children = new HashMap<>();
	}

	public TreeNode(char c) {
		event = c;
		frequency = 1;
		probability = 0.0;
		children = new HashMap<>();
	}
}
