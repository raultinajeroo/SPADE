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

				// Spade.setProbabilities(this);
				// this.printTree();
			}
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