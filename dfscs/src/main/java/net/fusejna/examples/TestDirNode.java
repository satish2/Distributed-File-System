package net.fusejna.examples;

import java.util.HashMap;

public class TestDirNode {
	
	/*This is directory Node.
	 * This can have files and subdirectories.
	 * All that information will be in this.
	 */
	
	private String directoryPath;
	private HashMap<String,TestDirNode> listOfDirectories = new HashMap<String,TestDirNode>();
	public HashMap<String, TestDirNode> getListOfDirectories() {
		return listOfDirectories;
	}

	public void setListOfDirectories(HashMap<String, TestDirNode> listOfDirectories) {
		this.listOfDirectories = listOfDirectories;
	}

	public HashMap<String, INODE> getListOfFiles() {
		return listOfFiles;
	}

	public void setListOfFiles(HashMap<String, INODE> listOfFiles) {
		this.listOfFiles = listOfFiles;
	}

	private HashMap<String,INODE> listOfFiles = new HashMap<String,INODE>();
	
	public TestDirNode(String directoryPath){
		this.directoryPath = directoryPath;
	}
	
	

}
