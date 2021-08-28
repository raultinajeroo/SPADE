import java.util.HashMap;
import java.util.ArrayList;
import java.util.List;

public class Tree {
	public TreeNode root;

	public Tree() {
		this.root = new TreeNode();
	}

	public void genContext(ArrayList<String> Ep) {
		ArrayList<TreeNode> tracker;
		List<TreeNode> toBeAdded, toBeRemoved;

		for (String str : Ep) {
			tracker = new ArrayList<TreeNode>();

			for (char event : str.toCharArray()) {
				toBeAdded = new ArrayList<TreeNode>();
				toBeRemoved = new ArrayList<TreeNode>();

				for (TreeNode node : tracker) {
					if (node.children.containsKey(event)) 
						node.children.get(event).frequency++;
					else 
						node.children.put(event, new TreeNode(event));
					toBeRemoved.add(node);
					toBeAdded.add(node.children.get(event));
				}

				for (TreeNode r : toBeRemoved) {tracker.remove(r);}
				for (TreeNode a : toBeAdded) {tracker.add(a);}

				if (this.root.children.containsKey(event))
					this.root.children.get(event).frequency++;
				else
					this.root.children.put(event, new TreeNode(event));

				tracker.add(this.root.children.get(event));
			}
		}
		this.setRootFrequency();
		this.setProbabilities();
	}

	public void setProbabilities() {
		for (char key : this.root.children.keySet()) {
			if (!this.root.children.isEmpty()) 
				setProbabilitiesRec(this.root.children.get(key), (double)this.root.frequency);
		}
	}

	private void setProbabilitiesRec(TreeNode node, double fr) {
		if (!node.children.isEmpty()) 
			setProbabilitiesRec(node.children.get(node.children.keySet().toArray()[0]), (double) node.frequency);
		node.probability = node.frequency / fr;
	}

	public void setRootFrequency() {
		this.root.frequency = 0;
		for (char key : this.root.children.keySet()) {
			this.root.frequency += this.root.children.get(key).frequency;
		}
	}

	public void printTree() {
		for (char key : this.root.children.keySet()) {
			TreeNode iter = this.root.children.get(key);
			while (true) {
				System.out.print(iter.event +"("+ iter.probability +")"+ " ");
				if (iter.children.isEmpty()) {break;}
				iter = iter.children.get(iter.children.keySet().toArray()[0]);
			}
			System.out.println();
		}
	}
}