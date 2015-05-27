package net.fusejna.examples;

import java.io.Serializable;

public class FileChunk implements Serializable {
	
	private String fullPath;
	public String getFullPath() {
		return fullPath;
	}

	public void setFullPath(String fullPath) {
		this.fullPath = fullPath;
	}

	private byte[] contents;
	
	public byte[] getContents() {
		return contents;
	}

	public void setContents(byte[] contents) {
		this.contents = contents;
	}

	public FileChunk (String fullPath, byte[] contents){
		this.fullPath = fullPath;
		this.contents = contents;
	}
}
