package net.fusejna.examples;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.Serializable;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;

import sun.misc.IOUtils;

import com.dropbox.core.DbxAccountInfo;
import com.dropbox.core.DbxEntry;
import com.dropbox.core.DbxException;
import com.dropbox.core.DbxWriteMode;
import com.dropbox.core.util.IOUtil;

public class INODE implements Serializable {
	/**
	 * 
	 */
	private static final long serialVersionUID = 1L;
	private String fullPath;
	private int fileType;
	private ArrayList<String> listOfFiles = new ArrayList<String>();

	private String localPath;
	private transient ByteBuffer fileContents = ByteBuffer.allocateDirect(0);
	private boolean gotLatestLocalCopy = false;
	protected boolean uploaded = false;
	private transient Integer accessCounter;
	
	private boolean deleted = false; /* This flag indicates if the file is deleted from the FS.
	 									Used in File Recovery.*/

	public INODE(final String fullpath, final int fileType) {
		this.setFullpath(fullpath);
		this.setFileType(fileType);
		this.accessCounter = 0;
		int beginIndex = DFSCS.mountPath.length();
		this.localPath = fullpath.substring(beginIndex);
		this.deleted = false;
	}

	public INODE(INODE inode) {
		this.listOfFiles = (inode.getListOfFiles());
		this.accessCounter = (inode.getAccessCounter());
		this.fileType = (inode.getFileType());
		this.fullPath = (inode.getFullpath());
		this.gotLatestLocalCopy = (inode.isGotLatest());
		this.localPath = (inode.getLocalPath());
	}

	public String getLocalPath() {
		return localPath;
	}

	public void setLocalPath(String localPath) {
		this.localPath = localPath;
	}

	public void setListOfFiles(ArrayList<String> listOfFiles) {
		this.listOfFiles = listOfFiles;
	}

	public ArrayList<String> getListOfFiles() {
		return listOfFiles;
	}

	public int getFileType() {
		return fileType;
	}

	public void setFileType(int fileType) {
		this.fileType = fileType;
	}

	public String getFullpath() {
		return fullPath;
	}

	public void setFullpath(String fullpath) {
		this.fullPath = fullpath;
		int beginIndex = DFSCS.mountPath.length();
		this.localPath = fullpath.substring(beginIndex);
	}

	public boolean isGotLatest() {
		return gotLatestLocalCopy;
	}

	public void setGotLatest(boolean gotLatest) {
		this.gotLatestLocalCopy = gotLatest;
	}

	public synchronized ByteBuffer download() {

		if (fullPath.equals(DFSCS.mountPath)) {
			return fileContents;
		}

		String dropboxlocalPath = "/tmp/" + localPath.replaceAll("/", ".") + "_db";
		String drivelocalPath = "/tmp/" + localPath.replaceAll("/", ".") + "_gd";

		String dropboxPath = localPath + "_db";
		String drivePath = localPath + "_gd";
		File dbFile = new File(dropboxlocalPath);
		com.dropbox.core.DbxEntry.File tempFile = DFSCS.dropBox.downloadFromDropBox(dropboxPath, dropboxlocalPath);
		if (tempFile == null) {
			dbFile.delete();
			return fileContents;
		}
		File drFile = new File(drivelocalPath);
		FileInputStream dbFileInputStream = null;
		FileInputStream drFileInputStream = null;

		try {
			System.out.println("Downloaded file from dropbox= " + dropboxlocalPath);
			com.google.api.services.drive.model.File f = DFSCS.googleDrive.downloadFile(drivePath, drivelocalPath);
			if (f != null) {
				DFSCS.googleDrive_metadata.put(fullPath, f.getId());
				DFSCS.dropbox_metadata.put(fullPath, tempFile);
				dbFileInputStream = new FileInputStream(dbFile);
				drFileInputStream = new FileInputStream(drFile);
				fileContents = ByteBuffer.allocateDirect((int) (dbFile.length() + drFile.length()));
				fileContents.position(0);
				fileContents.put(IOUtil.slurp(dbFileInputStream, (int) dbFile.length()));
				fileContents.put(IOUtil.slurp(drFileInputStream, (int) dbFile.length()));
			}
			this.setFileContents(fileContents);
			return fileContents;
		} catch (IOException e) {
			e.printStackTrace();
		} finally {
			gotLatestLocalCopy = true;
			try {
				if (dbFileInputStream != null)
					dbFileInputStream.close();
				if (drFileInputStream != null)
					drFileInputStream.close();
			} catch (IOException e) {
				//
			} finally {
				dbFile.delete();
				drFile.delete();
			}
		}
		return fileContents;
	}

	public ByteBuffer getFileContents() {
		return fileContents;
	}

	public synchronized int upload(ByteBuffer fileContents) {

		if (fileContents.equals(DFSCS.mountPath)) {
			return 0;
		}

		FileOutputStream dbfile = null;
		FileOutputStream drFile = null;
		FileInputStream dbfile_in = null;
		String dropboxlocalPath = "/tmp/" + localPath.replaceAll("/", ".") + "_db";
		String drivelocalPath = "/tmp/" + localPath.replaceAll("/", ".") + "_gd";

		String dropboxPath = localPath + "_db";
		String drivePath = localPath + "_gd";

		try {
			float googleDriveSize = (DFSCS.googleDrive.getAvailableSpaceInGoogleDrive());
			float googleDriveTotalSize = DFSCS.googleDrive.getTotalSpace();
			DbxAccountInfo dai = DFSCS.dbxclient.getAccountInfo();
			long quota = dai.quota.total;
			long normal = dai.quota.normal;
			long shared = dai.quota.shared;
			float dropBoxSize = (quota - normal - shared); // in
																			// bytes

			int fileSize = (int) fileContents.capacity();
			
			float ratio = dropBoxSize / googleDriveSize;
			int intoDropBox, intoGoogleDrive = 0;
			intoDropBox = (int) Math.ceil((ratio / (ratio + 1)) * fileSize);
			intoGoogleDrive = fileSize - intoDropBox;
			if(intoDropBox > dropBoxSize || intoGoogleDrive > googleDriveSize)
			{
				System.err.println("Unable to upload");
				return -1;
			}
			if(((intoDropBox+dropBoxSize) > 0.7*(quota)) || (intoGoogleDrive+googleDriveSize) > 0.7*(googleDriveTotalSize))
			{
				DFSCS.cleanCloud();
			}
			dbfile = new FileOutputStream(new File(dropboxlocalPath));
			drFile = new FileOutputStream(new File(drivelocalPath));
			if (fileSize != 0) {
				byte[] array1 = new byte[intoDropBox];
				byte[] array2 = new byte[intoGoogleDrive];
				fileContents.get(array1);
				fileContents.get(array2);
				dbfile.write(array1);
				drFile.write(array2);
				dbfile_in = new FileInputStream(dropboxlocalPath);
				com.dropbox.core.DbxEntry.File temp = DFSCS.dbxclient.uploadFile("/" + dropboxPath.replaceAll("/", "."), DbxWriteMode.force(), array1.length, dbfile_in);
				DFSCS.dropbox_metadata.put(fullPath, temp);
				System.out.println("Uploaded in dropbox = " + dropboxPath);
				String oldfileId = DFSCS.googleDrive_metadata.get(fullPath);
				String fileId = DFSCS.googleDrive.gdriveUploadFile(oldfileId, drivelocalPath, drivePath, DFSCS.folderId);
				DFSCS.googleDrive_metadata.put(fullPath, fileId);
				fileContents.clear();
				gotLatestLocalCopy = false;
				uploaded = true;
			}
		} catch (DbxException e) {
			e.printStackTrace();
			System.out.println("Unable to get Dropbox client to upload chunks");
		} catch (FileNotFoundException e) {
			System.out.println("Could not create chunk file");
			e.printStackTrace();
		} catch (IOException e) {
			System.out.println("COuld not write properly to chunk files");
			e.printStackTrace();
		} finally {
			try {
				if (drFile != null)
					drFile.close();
				if (dbfile != null)
					dbfile.close();
				if (dbfile_in != null)
					dbfile_in.close();
				File gdf = new File(drivelocalPath);
				File dbf = new File(dropboxlocalPath);
				gdf.delete();
				dbf.delete();
			} catch (IOException e) {
				System.out.println("Unable to close Output and Input streams");
				e.printStackTrace();
			}
		}
		return 0;
	}

	public void setFileContents(ByteBuffer fileContents) {
		this.fileContents = fileContents;
	}

	public Integer getAccessCounter() {
		return accessCounter;
	}

	public void incrementAccessCounter() {
		System.out.println("Incremented counter for file " + fullPath + " count = " + accessCounter);
		this.accessCounter++;
	}

	public void setAccessCounter(Integer accessCounter) {
		this.accessCounter = accessCounter;
	}
	
	public boolean isDeleted() {
		return deleted;
	}

	public void setDeleted(boolean deleted) {
		this.deleted = deleted;
	}

}
