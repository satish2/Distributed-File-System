package net.fusejna.examples;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Locale;

import org.mortbay.jetty.AbstractGenerator.Output;

import com.dropbox.core.DbxClient;
import com.dropbox.core.DbxEntry;
import com.dropbox.core.DbxException;
import com.dropbox.core.DbxRequestConfig;

public class DropBox {

	public DbxClient authenticateDropBox() {

		DbxRequestConfig config = new DbxRequestConfig("JavaTutorial/1.0", Locale.getDefault().toString());
		String accessToken = "LQCvzJwg16IAAAAAAAAdtdfgMhZ-qK5Zy_SuaQo0Xx_m6ofZ7uboYcvsnExWdZXB";
		DbxClient client = new DbxClient(config, accessToken);
		try {
			System.out.println("Linked account: " + client.getAccountInfo().displayName);
		} catch (DbxException e) {
			e.printStackTrace();
		}
		return client;
	}

	public DbxEntry.File downloadFromDropBox(String srcFilePath, String targetFileName) {

		FileOutputStream outputStream = null;
		File targetFile = null;
		try {
			srcFilePath = "/" + srcFilePath.replaceAll("/",".");
			targetFile = new File(targetFileName);
			outputStream = new FileOutputStream(targetFile);
			DbxRequestConfig config = new DbxRequestConfig("JavaTutorial/1.0", Locale.getDefault().toString());
			String accessToken = "LQCvzJwg16IAAAAAAAAdtdfgMhZ-qK5Zy_SuaQo0Xx_m6ofZ7uboYcvsnExWdZXB";
			DbxClient client = new DbxClient(config, accessToken);
			DbxEntry.File downloadedFile = client.getFile(srcFilePath, null, outputStream);
			return downloadedFile;
		} catch (IOException | DbxException e) {
			e.printStackTrace();
		} finally {
			try {
				if (outputStream != null)
					outputStream.close();
			} catch (IOException e) {
				e.printStackTrace();
			}
		}
		return null;
	}

	public void deleteFileFromDropBox(String path) {
		try {
			path = "/" + path.replaceAll("/",".");
			DbxRequestConfig config = new DbxRequestConfig("JavaTutorial/1.0", Locale.getDefault().toString());
			String accessToken = "LQCvzJwg16IAAAAAAAAdtdfgMhZ-qK5Zy_SuaQo0Xx_m6ofZ7uboYcvsnExWdZXB";
			DbxClient client = new DbxClient(config, accessToken);
			client.delete(path);
			System.out.println("Deleted " + path + " from dropbox");
		} catch (DbxException e) {
			System.out.println("File does not exist to delete it");
		} catch (Exception e) {
			e.printStackTrace();
		}
	}

	public com.dropbox.core.DbxEntry.File rename(String oldPath, String newPath) {
		FileOutputStream outputStream = null;
		try {
			oldPath = "/" + oldPath.replaceAll("/", ".");
			newPath = "/" + newPath.replaceAll("/", ".");
			DbxRequestConfig config = new DbxRequestConfig("JavaTutorial/1.0", Locale.getDefault().toString());
			String accessToken = "LQCvzJwg16IAAAAAAAAdtdfgMhZ-qK5Zy_SuaQo0Xx_m6ofZ7uboYcvsnExWdZXB";
			DbxClient client = new DbxClient(config, accessToken);
			if (client.getMetadata(oldPath) != null) {
				client.copy(oldPath, newPath);
				client.delete(oldPath);
			} else {
				return null;
			}
			outputStream = new FileOutputStream(new File("/home/temp"));
			return client.getFile(newPath, null, outputStream);
		} catch (Exception e) {
			e.printStackTrace();
		} finally {
			try {
				if (outputStream != null)
					outputStream.close();
				File temp = new File("/home/temp");
				if (temp != null)
					temp.delete();
			} catch (Exception e) {
				e.printStackTrace();
			}
		}
		return null;
	}
}
