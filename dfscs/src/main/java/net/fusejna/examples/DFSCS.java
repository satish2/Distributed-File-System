package net.fusejna.examples;

import java.io.*;
import java.nio.ByteBuffer;
import java.util.*;
import java.util.Map.Entry;

import com.dropbox.core.DbxClient;
import com.dropbox.core.DbxEntry;
import com.dropbox.core.DbxException;
import com.dropbox.core.DbxWriteMode;

import net.fusejna.DirectoryFiller;
import net.fusejna.ErrorCodes;
import net.fusejna.FuseException;
import net.fusejna.StructFuseFileInfo.FileInfoWrapper;
import net.fusejna.StructStat.StatWrapper;
import net.fusejna.types.TypeMode.ModeWrapper;
import net.fusejna.types.TypeMode.NodeType;
import net.fusejna.util.FuseFilesystemAdapterAssumeImplemented;

public class DFSCS extends FuseFilesystemAdapterAssumeImplemented {

	protected static String mountPath;
	private static HashMap<String, INODE> completeFS = new HashMap<String, INODE>();
	private static HashMap<String, INODE> cache = new HashMap<String, INODE>();
	private static final int CACHE_SIZE = 100 * 1024; // 100KB
	private static int available_cache_space = 0;
	private static final int FILE = 0;
	private static final int DIRECTORY = 1;
	protected static HashMap<String, String> googleDrive_metadata = new HashMap<String, String>(); // fullpath,gdriveID
	protected static HashMap<String, com.dropbox.core.DbxEntry.File> dropbox_metadata = new HashMap<String, com.dropbox.core.DbxEntry.File>();

	protected static GoogleDrive googleDrive = new GoogleDrive();
	protected static DropBox dropBox = new DropBox();
	protected static DbxClient dbxclient;
	protected static String folderId;
	private static boolean isInitialized = false;
	
	private static INODE findNode(String fullPath) {
		if (completeFS.containsKey(fullPath) == false) {
			return null;
		}
		return completeFS.get(fullPath);
	}

	/*
	 * param: fullPath returns : true if successfully updated or added in cache
	 * false, when it was not able to add to cache or overwrite in cache DONE:
	 * Also, removing from cache, set access counter to zero.
	 */
	private synchronized boolean add2Cache(String fullPath) {
		INODE thisNode = findNode(fullPath);
		if (thisNode != null) {
			ByteBuffer fileContents = thisNode.getFileContents();
			if (fileContents == null || fileContents.capacity() == 0)
				return false;

			if (isCached(fullPath)) {
				if ((available_cache_space + cache.get(fullPath).getFileContents().capacity()) - fileContents.capacity() > 0) {
					// If enough space is available when updating cache
					System.out.println("Already exists in cache: Overwriting with latest version");
					overwriteInCache(fullPath);
				} else {
					// TODO replace policy
					if (!replaceInCache(thisNode)) {
						// Since not enough space available to overwrite, remove
						// old
						// contents from cache.
						System.out.println("Already exists in cache: Could not replace or overwrite. Removing it");
						available_cache_space += cache.get(fullPath).getFileContents().capacity();
						cache.get(fullPath).setAccessCounter(0);
						cache.remove(fullPath);
						return false;
					}

				}

			} else { // if not cached.
				if (isEnoughSpaceAvailable(fileContents.capacity())) {
					System.out.println("Not cached initially: Caching it now");
					cache.put(fullPath, thisNode);
					available_cache_space = available_cache_space - fileContents.capacity();
				} else {
					// TODO not enough space available, trigger replace policy.
					// If true replaced with some other file.
					// else not.
					if (!replaceInCache(thisNode)) {
						System.out.println("Not in cache: Could not replace with other file");
						return false;
					}
				}
			}
		} else {
			return false;
		}

		return true;
	}

	private boolean replaceInCache(INODE thisNode) {
		if (thisNode == null)
			return false;
		String file2Replace = getReplaceFileNameInCache(thisNode.getAccessCounter());
		if (file2Replace == null)
			return false;
		else {
			INODE tmp = cache.get(file2Replace);
			if (tmp.getFileContents().capacity() + available_cache_space - thisNode.getFileContents().capacity() > 0) {
				available_cache_space = available_cache_space + tmp.getFileContents().capacity();
				tmp.setAccessCounter(0);
				cache.remove(file2Replace);
				cache.put(thisNode.getFullpath(), thisNode);
				System.out.println("Replaced " + file2Replace + " with " + thisNode.getFullpath() + " in cache");
			} else
				return false;

		}
		return true;
	}

	private boolean isEnoughSpaceAvailable(int capacity) {
		if (available_cache_space - capacity > 0 && capacity % 4096 != 0)
			return true;
		return false;
	}

	public static void main(String[] args) throws FuseException {
		if (args.length < 1) {
			System.exit(1);
		}
		mountPath = args[0];
		INODE rootNode = new INODE(mountPath, DIRECTORY);
		completeFS.put(mountPath + "/", rootNode);
		System.out.println(mountPath);
		available_cache_space = CACHE_SIZE;
		new DFSCS().log(true).mount(args[0]);
		if (!isInitialized) {
			for (Entry<String, INODE> entry : completeFS.entrySet()) {
				INODE node = (INODE) entry.getValue();
				node.setFileContents(ByteBuffer.allocate(0));
				node.setAccessCounter(new Integer(0));
				if(!node.isDeleted())
				{
					new File(entry.getKey().substring(mountPath.length()));
				}
			}
			isInitialized = true;
		}
	}

	public DFSCS() {
		try {
			initGDriveClient();
			initDropboxClient();
			if (getFSDataFromDropbox() == null)
				return;
			if (new File("/tmp/completefs").exists()) {
				FileInputStream fileIn = new FileInputStream("/tmp/completefs");
				ObjectInputStream objinStream = new ObjectInputStream(fileIn);
				@SuppressWarnings("unchecked")
				HashMap<String, INODE> temp = (HashMap<String, INODE>) objinStream.readObject();
				objinStream.close();
				fileIn.close();
				completeFS.putAll(temp);
				new File("/tmp/completefs").delete();
			}
		} catch (Exception e) {
			e.printStackTrace();
			System.out.println("Unable to create connection with cloud services");
		}
	}

	@Override
	public synchronized int release(String path, FileInfoWrapper info) {
		String fullPath = mountPath + path;
		INODE thisNode = findNode(fullPath);
		ByteBuffer temp = thisNode.getFileContents();
		if (temp != null) {
			if (isCached(fullPath) && temp.capacity() != 0) {
				System.out.println("Before at " + fullPath + "IN Cache: " + cache.get(fullPath).getFileContents().capacity() + "In FS:" + thisNode.getFileContents().capacity());
				INODE copyNode = new INODE(thisNode);
				copyNode.setFileContents(clone(temp));
				thisNode.setFileContents(ByteBuffer.allocate(0));
				thisNode.setGotLatest(false);
				cache.remove(fullPath);
				cache.put(fullPath, copyNode);
				System.out.println("After at " + fullPath + "IN Cache: " + cache.get(fullPath).getFileContents().capacity() + "In FS:" + thisNode.getFileContents().capacity());
			} else {
				temp.clear();
				thisNode.setGotLatest(false);
				thisNode.setFileContents(ByteBuffer.allocate(0));
				System.out.println("In FS:" + thisNode.getFileContents().capacity());
			}
		}
		System.out.println("Closing this file..." + path);
		return 0;
	}

	public static ByteBuffer clone(ByteBuffer buffer) {
		assert buffer != null;
		buffer.position(0);
		ByteBuffer clone = ByteBuffer.allocate(buffer.capacity());
		if (buffer.hasArray()) {
			System.arraycopy(buffer.array(), buffer.arrayOffset() + buffer.position(), clone.array(), 0, buffer.remaining());
		} else {
			clone.put(buffer.duplicate());
			clone.flip();
		}
		return clone;
	}

	@Override
	public void afterUnmount(File mountPoint) {
		FileOutputStream fileOut = null;//
		ObjectOutputStream out = null;//
		File temp = null;
		try {
			for (Entry<String, INODE> entry : cache.entrySet()) {
				INODE thisNode = entry.getValue();
				/*
				 * If did not upload, then do it. But also possible, he did
				 * upload, but made modifications later.
				 */
				if (thisNode != null) {
					if (!thisNode.uploaded) {
						thisNode.upload(thisNode.getFileContents());
					}
				}
			}
			temp = new File("/tmp/completefs");
			fileOut = new FileOutputStream(temp);
			out = new ObjectOutputStream(fileOut);
			out.writeObject(completeFS);
			out.close();
			fileOut.close();
			System.out.printf("Serialized data is saved in /tmp/completefs");
			putFSDataInDropbox();
			completeFS.clear();
			super.afterUnmount(mountPoint);
		} catch (IOException e) {
			e.printStackTrace();
		} catch (DbxException e) {
			e.printStackTrace();
		} finally {
			try {
				if (fileOut != null)
					fileOut.close();
				if (out != null)
					out.close();
				if (temp != null)
					temp.delete();
			} catch (Exception e) {
				e.printStackTrace();
			}
		}
	}

	@Override
	public int access(String path, int access) {
		// System.out.println("ACCESS: " + path);
		return 0;
	}

	@Override
	public int create(String path, ModeWrapper mode, FileInfoWrapper info) {
		INODE thisNode = null;
		boolean deletedFile = false;
		boolean createNew = false;
		
		if (completeFS.containsKey(mountPath + path) == true) {
			thisNode = completeFS.get(mountPath+path);
			if(thisNode.isDeleted()==false)
				return ErrorCodes.EALREADY();
			deletedFile = true;
		}
		if(deletedFile)
		{
			//thisNode.setDeleted(false);
			System.out.println("There is a deleted file with the same name\n"
								+ "Do you want to recover it?");
			Scanner scan = new Scanner(System.in);
			createNew = !(Boolean.parseBoolean(scan.nextLine()));
		}
		else
		{
			createNew = true;
		}
		
		if(!createNew)
		{
			thisNode.setDeleted(false);
		}
		else
		{
			String fullPath = mountPath + path;
			INODE newNode = new INODE(fullPath, FILE);
	
			String currentDir = fullPath.substring(0, fullPath.lastIndexOf("/") + 1);
			if (!currentDir.equals(mountPath + "/")) {
				currentDir = fullPath.substring(0, fullPath.lastIndexOf("/"));
			}
			INODE dirNode = completeFS.get(currentDir);
			dirNode.getListOfFiles().add(fullPath);
			completeFS.put(fullPath, newNode);
		}
		
		return 0;
	}

	@Override
	public synchronized int getattr(String path, StatWrapper stat) {
		String fullPath = mountPath + path;
		INODE thisNode = findNode(fullPath);
		if (thisNode == null) {
			return -ErrorCodes.ENOENT();
		}
		else
		{
			if(thisNode.getFileType() != DIRECTORY) 
				stat.setMode(NodeType.DIRECTORY);
			else
				stat.setMode(NodeType.FILE);
		}
		ByteBuffer fileContents = thisNode.getFileContents();
		if (thisNode.getFileType() == FILE) {
			stat.setMode(NodeType.FILE);
			if (!isCached(fullPath) && (!thisNode.isGotLatest() || fileContents == null)) {
				System.out.println("Not found in Cache : Downloading from cloud:" + fullPath);
				fileContents = thisNode.download();
			} else if (isCached(fullPath)) {
				System.out.println("Found in cache: directly reading from cache:" + fullPath);
				fileContents = cache.get(fullPath).getFileContents();
			}
			if (fileContents != null) {
				stat.size(fileContents.capacity());
				System.out.println("CONTENT SIZE:: " + fileContents.capacity() + " path = " + fullPath);
			}
		} else {
			stat.setMode(NodeType.DIRECTORY);
		}
		return 0;
	}

	/*
	 * @Override public int flush(String path, FileInfoWrapper info) { TestNode
	 * newNode = new TestNode(mountPath + path, FILE);
	 * newNode.setGotLatest(false); newNode.getFileContents().clear();
	 * newNode.getFileContents().position(0); return 0; }
	 */

	@Override
	public synchronized int open(final String path, final FileInfoWrapper info) {
		String fullPath = mountPath+path;
		INODE thisNode = findNode(fullPath);
		
		int createNew = 0;
		if (thisNode != null) 
		{
			if (thisNode.getAccessCounter() == null) 
			{
				thisNode.setAccessCounter(0);
			}
			if(thisNode.isDeleted()==true)
			{
				System.out.println("There is a deleted file with the same name\n"
						+ "Do you want to recover it?");
				/*Console c = System.console();
				createNew = Integer.parseInt(c.readLine());*/
				createNew = 1;
				if(createNew==1)
				{
					thisNode.setDeleted(false);
				}
				else
				{
					create(path, new ModeWrapper(33152), info);
				}
			}
			thisNode.incrementAccessCounter();
		}
		return 0;
	}

	private String getReplaceFileNameInCache(int pingCount) {
		int min = 10000;
		String minPath = null;
		for (Entry<String, INODE> entry : cache.entrySet()) {
			int temp = entry.getValue().getAccessCounter();
			if (min > temp) {
				min = temp;
				minPath = entry.getKey();
			}
		}
		if (minPath != null && cache.get(minPath).getAccessCounter() < pingCount) {
			return minPath;
		} else
			return null;
	}

	@Override
	public int mkdir(final String path, final ModeWrapper mode) {
		if (completeFS.containsKey(mountPath + path) == true) {
			return -ErrorCodes.EALREADY();
		}
		String fullPath = mountPath + path;
		INODE newNode = new INODE(fullPath, DIRECTORY);

		String currentDir = fullPath.substring(0, fullPath.lastIndexOf("/") + 1);
		if (!currentDir.equals(mountPath + "/")) {
			currentDir = fullPath.substring(0, fullPath.lastIndexOf("/"));
		}
		INODE dirNode = completeFS.get(currentDir);
		dirNode.getListOfFiles().add(fullPath);

		completeFS.put(fullPath, newNode);
		return 0;
	}

	@Override
	public int rmdir(String path) {
		if (completeFS.containsKey(mountPath + path) == false) {
			return -ErrorCodes.EALREADY();
		}
		String fullPath = mountPath + path;
		INODE rmNode = completeFS.get(fullPath);
		if (rmNode.getFileType() == DIRECTORY) {
			Iterator<String> listOfFiles = rmNode.getListOfFiles().iterator();
			while (listOfFiles.hasNext()) {
				String tempName = listOfFiles.next();
				if(completeFS.get(tempName) == null)
					continue;
				if (completeFS.get(tempName).getFileType() == DIRECTORY) {
					rmdir(tempName);
				} else {
					if (isCached(tempName)) {
						System.out.println("Removing this file from cache " + tempName);
						available_cache_space = available_cache_space + cache.get(tempName).getFileContents().capacity();
						cache.get(tempName).setAccessCounter(0);
						cache.remove(tempName);
					}
					completeFS.get(tempName).setDeleted(true);
					
					if(path.equalsIgnoreCase("/4913") || path.endsWith("~"))
					{
						completeFS.remove(fullPath);
						removeInCloud(fullPath, rmNode);
					}
				}
			}
		} else {
			if (isCached(fullPath)) {
				System.out.println("Removing this file from cache " + fullPath);
				available_cache_space = available_cache_space + cache.get(fullPath).getFileContents().capacity();
				cache.get(fullPath).setAccessCounter(0);
				cache.remove(fullPath);
			}
			completeFS.get(fullPath).setDeleted(true);
			if(path.equalsIgnoreCase("/4913") || path.endsWith("~"))
			{
				completeFS.remove(fullPath);
				removeInCloud(fullPath, rmNode);
			}
			
		}
		return 0;
	}

	@Override
	public int unlink(String path) {
		rmdir(path);
		//completeFS.remove(mountPath + path);
		return 0;
	}

	private static void removeInCloud(String fullPath, INODE rmNode) {
		try {
			if (dropBox != null) {
				dropbox_metadata.remove(fullPath);
				dropBox.deleteFileFromDropBox(rmNode.getLocalPath() + "_db");
			}
			if (googleDrive != null && googleDrive_metadata.get(fullPath) != null) {
				googleDrive.deleteFile(googleDrive_metadata.get(fullPath));
				googleDrive_metadata.remove(fullPath);
				System.out.println("Deleted " + fullPath + "from google drive");
			}

		} catch (Exception e) {
			System.out.println("Unable to delete file from google drive while deleting");
			e.printStackTrace();
		}
	}

	@Override
	public synchronized int read(String path, ByteBuffer buffer, long size, long offset, FileInfoWrapper info) {
		String fullPath = mountPath + path;
		INODE thisNode = findNode(mountPath + path);
		if (thisNode == null) {
			return ErrorCodes.ENOENT();
		}
		int numBytes;
		ByteBuffer readContents = thisNode.getFileContents();
		System.out.println("Reading " + path);
		// Checks if it not cached and (if thisNode has not got latest, it it
		// has latest it means it already downloaded and
		// its fileContents is filled. Its fileContents can be null. Especially,
		// when re-creating files upon mounting File system)
		if (!isCached(fullPath) && (!thisNode.isGotLatest() || readContents == null)) {
			System.out.println("Not found in Cache : Downloading from cloud :" + fullPath);
			readContents = thisNode.download();
		} else if (isCached(fullPath)) {
			// If the file is cached, then getting byte buffer from cache.
			System.out.println("Found in cache: directly reading from cache:" + fullPath);
			readContents = cache.get(fullPath).getFileContents();
		} else if (readContents == null) {
			// even after downloading or loading from cache if readContents are
			// null. Then returns 0
			buffer.put(ByteBuffer.allocate(0));
			buffer.position(0);
			return 0;
		}
		if (readContents != null) {
			if (size < readContents.capacity() - offset) {
				numBytes = (int) size;
			} else if (readContents.capacity() - offset < 0) {
				numBytes = (int) (4096 + (readContents.capacity() - offset));
			} else {
				numBytes = (int) (readContents.capacity() - offset);
			}
			byte[] readByte = new byte[numBytes];
			readContents.position((int) offset);
			readContents.get(readByte, 0, numBytes);
			readContents.position(0);
			buffer.put(readByte);
			buffer.position(0);
			// If the file is not cached and there is enough space available for
			// caching, we will cache it.'
			if (add2Cache(fullPath)) {
				System.out.println("added to cache");
			}
			return numBytes;
		}
		return 0;
	}

	@Override
	public int readdir(String path, DirectoryFiller filler) {
		INODE thisNode = findNode(mountPath + path);
		if (thisNode == null) {
			completeFS.remove(mountPath + path);
			return 0;
		}

		if (thisNode.getFileType() != DIRECTORY) {
			return -ErrorCodes.ENOTDIR();
		}

		ArrayList<String> filesInDir = new ArrayList<String>();
		Iterator<String> keyIt = completeFS.keySet().iterator();
		while (keyIt.hasNext()) {
			String absolutePath = keyIt.next();
			if(path.equals("/")){
				if (absolutePath.startsWith(mountPath + path) && (!absolutePath.equals(mountPath + path))) {
					if (completeFS.get(absolutePath) != null && !completeFS.get(absolutePath).isDeleted())
						filesInDir.add(absolutePath);
					/*else
						completeFS.remove(absolutePath);*/
				}
			} else {
				if (absolutePath.startsWith(mountPath + path + "/") && (!absolutePath.equals(mountPath + path))) {
					if (completeFS.get(absolutePath) != null  && !completeFS.get(absolutePath).isDeleted())
						filesInDir.add(absolutePath);
					/*else
						completeFS.remove(absolutePath);*/
				}
			}
		}

		if (thisNode != null) {
			filler.add(filesInDir);
		}
		return 0;
	}

	@Override
	public synchronized int rename(String oldName, String newName) {
		String key = mountPath + oldName;
		if (completeFS.containsKey(key)) {
			INODE tn = completeFS.get(key);
			if (tn.getFileType() == DIRECTORY) {
				Iterator<String> allNames = completeFS.keySet().iterator();
				HashMap<String, String> oldNew = new HashMap<String, String>();
				// Iterates through all names and puts in oldNew hashmap, all
				// those files/folders that are 'sub-' to this directory
				while (allNames.hasNext()) {
					String subFilesOldName = allNames.next();
					String newName_1 = "";
					if (subFilesOldName.startsWith(key + "/")) {
						newName_1 = subFilesOldName.replace(mountPath + oldName, mountPath + newName);
						oldNew.put(subFilesOldName, newName_1);
					}
				}

				// In hashmap oldNew, got all files whose names I have to change

				Iterator<String> renameList = oldNew.keySet().iterator();
				while (renameList.hasNext()) {
					String subFileOldName = renameList.next();
					INODE subFileNode = completeFS.get(subFileOldName);

					if (subFileNode.getFileType() == FILE) {
						if (isCached(subFileOldName)) {
							INODE tmp = cache.remove(subFileOldName);
							tmp.setFullpath(oldNew.get(subFileOldName));
							cache.put(oldNew.get(subFileOldName), tmp);
						}
						String temp1 = subFileOldName.substring(mountPath.length());
						String temp2 = oldNew.get(subFileOldName).substring(mountPath.length());
						renameInCloud(subFileNode, temp1, temp2);

					}
					completeFS.remove(subFileOldName);
					subFileNode.setFullpath(oldNew.get(subFileOldName));
					completeFS.put(oldNew.get(subFileOldName), subFileNode);
				}
			} else {
				if (isCached(key)) {
					INODE tmp = cache.remove(key);
					tmp.setFullpath(mountPath+newName);
					cache.put(mountPath + newName, tmp);
				}
				tn.setFullpath(mountPath + newName);
				completeFS.remove(key);
				completeFS.put(mountPath + newName, tn);
				renameInCloud(tn, oldName, newName);

			}

		}
		// System.out.println("File with this name does not exist to rename");
		return 0;
	}

	public void renameInCloud(INODE tn, String oldName, String newName) {

		String dropboxPath = oldName + "_db";
		String dropboxNewPath = newName + "_db";

		try {
			if (dropBox != null) {
				com.dropbox.core.DbxEntry.File newFile = dropBox.rename(dropboxPath, dropboxNewPath);
				if (newFile == null)
					return;
				dropbox_metadata.remove(mountPath + oldName);
				dropbox_metadata.put(mountPath + newName, newFile);
			}
			String oldId = googleDrive_metadata.get(mountPath + oldName);
			if (googleDrive != null && oldId != null) {
				String newId = googleDrive.rename(oldId, newName.replaceAll("/", ".") + "_gd");
				googleDrive_metadata.remove(mountPath + oldName);
				googleDrive_metadata.put(mountPath + newName, newId);
			}
		} catch (Exception e) {
			System.out.println("Unable to delete file from google drive while deleting");
			e.printStackTrace();
		}
	}

	public static void cleanCloud(){
		for(INODE tempInode: completeFS.values())
		{
			if(tempInode.isDeleted())
			{
				completeFS.remove(tempInode.getFullpath());
				removeInCloud(tempInode.getFullpath(), tempInode);
			}
		}
	}
	
	@Override
	public synchronized int write(String path, ByteBuffer buf, long bufSize, long writeOffset, FileInfoWrapper wrapper) {
		int lastIndexAfterWrite = (int) (writeOffset + bufSize);
		String fullPath = mountPath + path;
		INODE thisNode = findNode(fullPath);
		if (thisNode == null) {
			return ErrorCodes.ENOENT();
		}
		ByteBuffer fileContents = thisNode.getFileContents();
		if (lastIndexAfterWrite > fileContents.capacity()) {
			ByteBuffer newBigBuffer = ByteBuffer.allocateDirect(lastIndexAfterWrite);
			newBigBuffer.put(fileContents);
			newBigBuffer.position(0);
			thisNode.setFileContents(newBigBuffer);
		}
		byte[] inputBytes = new byte[(int) bufSize];
		buf.get(inputBytes, 0, (int) bufSize);
		fileContents = thisNode.getFileContents();
		fileContents.position((int) writeOffset);
		fileContents.put(inputBytes);
		fileContents.position(0);
		if (bufSize < 4096) {
			// Writes only when bufSize is less than 4096 i.,e final write
			if(thisNode.upload(thisNode.getFileContents()) != 0)
			{
				return -ErrorCodes.E2BIG();
			}
			
			add2Cache(fullPath);
		}
		return (int) bufSize;
	}

	/*
	 * Overwrites in cache, only when file is already in cache and overwriting
	 * it will not overflow the cache. Also, only when overwriting contents are
	 * different compared to previous, indicated by wroteInCache.
	 */
	private void overwriteInCache(String fullPath) {
		INODE thisNode = findNode(fullPath);
		if (thisNode != null) {
			available_cache_space += cache.get(fullPath).getFileContents().capacity();
			cache.remove(fullPath);
			cache.put(fullPath, thisNode);
			available_cache_space = available_cache_space - thisNode.getFileContents().capacity();
		}
	}

	private boolean isCached(String fullPath) {
		if (cache.containsKey(fullPath)) {
			return true;
		}
		return false;
	}

	public static GoogleDrive initGDriveClient() throws FileNotFoundException, IOException {

		String url = googleDrive.gdriveGetAuthLink();
		System.out.println("Please open the following URL in your browser then type the authorization code:");
		System.out.println("  " + url);
		BufferedReader br = new BufferedReader(new InputStreamReader(System.in));
		System.out.println("Please enter gdrive url here: ");
		String code = br.readLine();
		br.close();
		googleDrive.gdriveAuthorize(code);
		folderId = googleDrive.gdriveCreateFolder();
		return googleDrive;
	}

	public static DbxClient initDropboxClient() throws DbxException {
		dbxclient = dropBox.authenticateDropBox();
		return dbxclient;
	}

	private static void putFSDataInDropbox() throws DbxException, IOException {
		File fsdata = new File("/tmp/completefs");
		FileInputStream fin = new FileInputStream(fsdata);
		dbxclient.uploadFile("/34832482374983274832789472398", DbxWriteMode.force(), fsdata.length(), fin);
	}

	private static DbxEntry.File getFSDataFromDropbox() {
		return dropBox.downloadFromDropBox("34832482374983274832789472398", "/tmp/completefs");
	}

}
