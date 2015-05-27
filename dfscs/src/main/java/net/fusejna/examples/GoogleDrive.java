package net.fusejna.examples;

import java.io.BufferedReader;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.google.api.client.googleapis.auth.oauth2.GoogleAuthorizationCodeFlow;
import com.google.api.client.googleapis.auth.oauth2.GoogleCredential;
import com.google.api.client.googleapis.auth.oauth2.GoogleTokenResponse;
import com.google.api.client.http.FileContent;
import com.google.api.client.http.HttpTransport;
import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.api.client.json.JsonFactory;
import com.google.api.client.json.jackson2.JacksonFactory;
import com.google.api.client.util.IOUtils;
import com.google.api.services.drive.Drive;
import com.google.api.services.drive.DriveScopes;
import com.google.api.services.drive.Drive.Files;
import com.google.api.services.drive.model.About;
import com.google.api.services.drive.model.File;
import com.google.api.services.drive.model.FileList;
import com.google.api.services.drive.model.ParentReference;

public class GoogleDrive {

	private static String CLIENT_ID = "725351805928-gt9ph8d40kkr90p90s11hmej2vr551km.apps.googleusercontent.com";
	private static String CLIENT_SECRET = "_7m0SfFuZOBIu24CA21sHPq4";
	private static String REDIRECT_URI = "urn:ietf:wg:oauth:2.0:oob";

	HttpTransport httpTransport = new NetHttpTransport();
	JsonFactory jsonFactory = new JacksonFactory();
	private static GoogleAuthorizationCodeFlow flow;

	public String gdriveGetAuthLink() {
		flow = new GoogleAuthorizationCodeFlow.Builder(httpTransport, jsonFactory, CLIENT_ID, CLIENT_SECRET, Arrays.asList(DriveScopes.DRIVE)).setAccessType("offline")
				.setApprovalPrompt("auto").build();
		String url = flow.newAuthorizationUrl().setRedirectUri(REDIRECT_URI).build();
		return url;
	}

	public void gdriveAuthorize(String code) {
		try {
			GoogleTokenResponse response = flow.newTokenRequest(code).setRedirectUri(REDIRECT_URI).execute();
			GoogleCredential credential = new GoogleCredential.Builder().setTransport(httpTransport).setJsonFactory(jsonFactory).setClientSecrets(CLIENT_ID, CLIENT_SECRET).build()
					.setFromTokenResponse(response);

			String accessToken = credential.getAccessToken();
			System.out.println("accessToken" + accessToken);
			PrintWriter aout = new PrintWriter("accesstoken.txt");
			aout.println(accessToken);
			aout.close();

			String refreshToken = credential.getRefreshToken();
			System.out.println("refreshToken" + refreshToken);
			PrintWriter rout = new PrintWriter("refreshtoken.txt");
			rout.println(refreshToken);
			rout.close();
		} catch (IOException ex) {
			System.out.println("exception in gd4-gdriveauthorize  ");
			Logger.getLogger(GoogleDrive.class.getName()).log(Level.SEVERE, null, ex);
		}
	}

	public String gdriveCreateFolder() throws FileNotFoundException, IOException {
		BufferedReader brd1 = new BufferedReader(new FileReader("accesstoken.txt"));
		String aTok = brd1.readLine();
		brd1.close();

		BufferedReader brd2 = new BufferedReader(new FileReader("refreshtoken.txt"));
		String rTok = brd2.readLine();
		brd2.close();

		GoogleCredential credential1 = new GoogleCredential.Builder().setJsonFactory(jsonFactory).setTransport(httpTransport).setClientSecrets(CLIENT_ID, CLIENT_SECRET).build();
		credential1.setAccessToken(aTok);
		credential1.setRefreshToken(rTok);
		Drive service = new Drive.Builder(httpTransport, jsonFactory, credential1).build();

		List<File> result = new ArrayList<File>();
		Files.List request = service.files().list();
		request.setQ("title='ESA_GRP4'");
		FileList files = request.execute();
		result = files.getItems();
		File file = new File();
		if (result.size() == 0) {
			File body1 = new File();
			body1.setTitle("ESA_GRP4");
			body1.setMimeType("application/vnd.google-apps.folder");
			file = service.files().insert(body1).execute();
		} else {
			file = result.get(0);
		}
		return file.getId();
	}

	public String gdriveUploadFile(String oldFileId, String srcFile, String filepath, String folderid) throws FileNotFoundException, IOException {
		String targetPath = filepath.replace('/', '.');
		BufferedReader brd1 = new BufferedReader(new FileReader("accesstoken.txt"));
		String aTok = brd1.readLine();
		brd1.close();

		BufferedReader brd2 = new BufferedReader(new FileReader("refreshtoken.txt"));
		String rTok = brd2.readLine();
		brd2.close();

		GoogleCredential credential1 = new GoogleCredential.Builder().setJsonFactory(jsonFactory).setTransport(httpTransport).setClientSecrets(CLIENT_ID, CLIENT_SECRET).build();
		credential1.setAccessToken(aTok);
		credential1.setRefreshToken(rTok);
		Drive service = new Drive.Builder(httpTransport, jsonFactory, credential1).build();
		File body = new File();
		body.setTitle("textfile");
		body.setDescription("Uploaded by ESA_GRP4");
		// body.setMimeType("text/plain");

		java.io.File driveChunk = new java.io.File(srcFile);
		FileContent mediaContent = new FileContent("text/plain", driveChunk);
		if (oldFileId != null) {
			try {
				System.out.println("Overwriting this file " + targetPath);
				this.deleteFile(oldFileId);
			} catch (Exception e) {
				System.out.println("Could not replace already existing file with overwritten one");
			}
		}

		File body2 = new File();
		body2.setTitle(targetPath);
		// body2.setMimeType("text/plain");
		body2.setParents(Arrays.asList(new ParentReference().setId(folderid)));
		File file2 = service.files().insert(body2, mediaContent).execute();
		System.out.println("uploaded file = " + file2.getTitle() + file2.getWebContentLink());
		return file2.getId();
	}

	public long getAvailableSpaceInGoogleDrive() {
		long availableSpaceInGoogleDrive = 0;
		try {
			BufferedReader brd1 = new BufferedReader(new FileReader("accesstoken.txt"));
			String aTok = brd1.readLine();
			brd1.close();

			BufferedReader brd2 = new BufferedReader(new FileReader("refreshtoken.txt"));
			String rTok = brd2.readLine();
			brd2.close();

			GoogleCredential credential1 = new GoogleCredential.Builder().setJsonFactory(jsonFactory).setTransport(httpTransport).setClientSecrets(CLIENT_ID, CLIENT_SECRET)
					.build();
			credential1.setAccessToken(aTok);
			credential1.setRefreshToken(rTok);
			Drive service = new Drive.Builder(httpTransport, jsonFactory, credential1).build();
			About about = service.about().get().execute();
			availableSpaceInGoogleDrive = (about.getQuotaBytesTotal() - about.getQuotaBytesUsed());
		} catch (IOException e) {
			e.printStackTrace();
		}
		return availableSpaceInGoogleDrive;

	}

	public String rename(String oldID, String newTitle) {
		try {
			newTitle.replace('/', '.');
			BufferedReader brd1 = new BufferedReader(new FileReader("accesstoken.txt"));
			String aTok = brd1.readLine();
			brd1.close();

			BufferedReader brd2 = new BufferedReader(new FileReader("refreshtoken.txt"));
			String rTok = brd2.readLine();
			brd2.close();

			GoogleCredential credential1 = new GoogleCredential.Builder().setJsonFactory(jsonFactory).setTransport(httpTransport).setClientSecrets(CLIENT_ID, CLIENT_SECRET)
					.build();
			credential1.setAccessToken(aTok);
			credential1.setRefreshToken(rTok);
			Drive service = new Drive.Builder(httpTransport, jsonFactory, credential1).build();

			File file = service.files().get(oldID).execute();
			file.setTitle(newTitle);

			Files.Patch patchRequest = service.files().patch(oldID, file);
			patchRequest.setFields("title").execute();
			return oldID;
		} catch (IOException e) {
			System.out.println("An error occurred: " + e);
		}
		return null;
	}

	public File downloadFile(String fileName, String destFileName) {
		fileName = fileName.replace('/', '.');
		fileName = fileName.replace("'", "\\'");
		BufferedReader brd1;
		try {
			brd1 = new BufferedReader(new FileReader("accesstoken.txt"));
			String aTok = brd1.readLine();
			brd1.close();

			BufferedReader brd2 = new BufferedReader(new FileReader("refreshtoken.txt"));
			String rTok = brd2.readLine();
			brd2.close();

			GoogleCredential credential1 = new GoogleCredential.Builder().setJsonFactory(jsonFactory).setTransport(httpTransport).setClientSecrets(CLIENT_ID, CLIENT_SECRET)
					.build();
			credential1.setAccessToken(aTok);
			credential1.setRefreshToken(rTok);
			Drive service = new Drive.Builder(httpTransport, jsonFactory, credential1).build();

			List<File> result = new ArrayList<File>();
			Files.List request = service.files().list();
			request.setQ("title='" + fileName + "'");
			FileList files = request.execute();
			result = files.getItems();
			File file = new File();
			if (result.size() == 0) {
				return null;
			}
			file = result.get(0);

			FileOutputStream outputStream = new FileOutputStream(destFileName);

			if (file.getDownloadUrl() != null && file.getDownloadUrl().length() > 0) {
				InputStream temp = service.files().get(file.getId()).executeMediaAsInputStream();
				IOUtils.copy(temp, outputStream);
			}
			outputStream.close();
			System.out.println("Downloaded file = " + file);
			return file;
		} catch (Exception e1) {
			e1.printStackTrace();
		}
		return null;
	}

	public void deleteFile(String id) throws Exception {

		BufferedReader brd1 = new BufferedReader(new FileReader("accesstoken.txt"));
		String aTok = brd1.readLine();
		brd1.close();

		BufferedReader brd2 = new BufferedReader(new FileReader("refreshtoken.txt"));
		String rTok = brd2.readLine();
		brd2.close();

		GoogleCredential credential1 = new GoogleCredential.Builder().setJsonFactory(jsonFactory).setTransport(httpTransport).setClientSecrets(CLIENT_ID, CLIENT_SECRET).build();
		credential1.setAccessToken(aTok);
		credential1.setRefreshToken(rTok);
		Drive service = new Drive.Builder(httpTransport, jsonFactory, credential1).build();
		service.files().delete(id).execute();
	}

}
