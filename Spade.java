import java.io.File;         
import java.io.FileNotFoundException;
import java.util.Scanner;
import java.io.FileWriter;
import java.util.ArrayList;

public class Spade {
	public static void main(String[] args) throws FileNotFoundException {
		File myFile = new File("canvas.csv");
		Scanner scnr = new Scanner(myFile);

		String sequence = "";
		String line = scnr.nextLine();
		String[] attribute = line.split(","); // 23 sensors

		for (int i = 5; i < attribute.length - 2; i++) {
			if (attribute[i].equals("ON") || attribute[i].equals("PRESENT") || (!attribute[i].equals("0.0") && isDouble(attribute[i])))
				sequence += (char)(60 + i);
		}

		while (scnr.hasNextLine()) {
			String[] previousAttr = line.split(",");
			line = scnr.nextLine();
			attribute = line.split(","); // attributes are what I called the sensor data points

			for (int i = 5; i < attribute.length - 2; i++) {
				if (previousAttr[i].equals("ON") || previousAttr[i].equals("PRESENT") || (!previousAttr[i].equals("0.0") && isDouble(previousAttr[i]))) {
					if (attribute[i].equals("OFF") || attribute[i].equals("ABSENT") || attribute[i].equals("0.0")) {
						sequence += (char)(60 + i + 32);
					}
				}
				else if (previousAttr[i].equals("OFF") || previousAttr[i].equals("ABSENT") || previousAttr[i].equals("0.0")) {
					if (attribute[i].equals("ON") || attribute[i].equals("PRESENT") || (!attribute[i].equals("0.0") && isDouble(attribute[i]))) {
						sequence += (char)(60 + i);
					}
				}
			}
		}

		test(sequence);
	}

	public static void test(String sequence) {
		Tree tree = new Tree();
		ArrayList<String> eps = getEpisodes(sequence);
		tree.genContext(eps);

		TreeNode arr[] = new TreeNode[tree.root.children.keySet().size()];
		int attmptCount = 0, correctAttmpt = 0;

		int it = 0;
		for (char key : tree.root.children.keySet())
			arr[it++] = tree.root.children.get(key);
		sort(arr);

		for (char event : sequence.toCharArray()) {
			for (TreeNode probTest : arr) {
				attmptCount++;
				if (probTest.event != event) {continue;}
				correctAttmpt++;
				break;
			}
		}

		System.out.println("Correlation Coefficient: " + (double) correctAttmpt / attmptCount * 100 + "%");
	}

	public static void sort(TreeNode[] nodes) {
		// NEED TO IMPLEMENT BETTER SORTING - current : bubble sort
	    for (int i = 0; i < nodes.length - 1; i++) {
	    	for (int j = 0; j < nodes.length - i - 1; j++) {
	    		if (nodes[j].probability > nodes[j+1].probability) {
	    			TreeNode temp = nodes[j];
                    nodes[j] = nodes[j+1];
                    nodes[j+1] = temp;
	    		}
	    	}
	    }
	}

	public static Tree train(String trainSequence) {
		Tree tree = new Tree();
		ArrayList<String> trainEps = getEpisodes(trainSequence);
		tree.genContext(trainEps);



		double max = 0;
		String highProb = "";

		for (char key : tree.root.children.keySet()) {
			TreeNode node = tree.root.children.get(key);
			if (node.probability == max) {
				highProb += ", " + node.event;
			}
			else if (node.probability > max) {
				highProb = "" + node.event;
				max = node.probability;
			}
		}
		return tree;
	}

	public static ArrayList<String> getEpisodes(String sequence) {
		ArrayList<String> arr = new ArrayList<String>();
		for (int i = 0; i < sequence.length(); i++) {
			char c = sequence.charAt(i);
			if (Character.isUpperCase(c)) {
				String seq = sequence.substring(i);
				int charLoc = seq.indexOf(Character.toLowerCase(c));
				if (charLoc != -1) {
					arr.add(sequence.substring(i,charLoc+i+1));
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

	// Calculate Probability
}