package info.guardianproject.securereader;

import info.guardianproject.onionkit.trust.StrongHttpsClient;
import info.guardianproject.securereader.SyncService.SyncTask;
import info.guardianproject.iocipher.File;
import info.guardianproject.iocipher.FileInputStream;
import info.guardianproject.iocipher.FileOutputStream;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;

import javax.net.ssl.HttpsURLConnection;

import android.net.Proxy;
import android.os.Handler;
import android.os.Message;
import android.os.Messenger;
import android.os.RemoteException;
import android.util.Log;
import ch.boye.httpclientandroidlib.HttpEntity;
import ch.boye.httpclientandroidlib.HttpHost;
import ch.boye.httpclientandroidlib.HttpResponse;
import ch.boye.httpclientandroidlib.HttpStatus;
import ch.boye.httpclientandroidlib.client.ClientProtocolException;
import ch.boye.httpclientandroidlib.client.HttpClient;
import ch.boye.httpclientandroidlib.client.methods.HttpGet;
import ch.boye.httpclientandroidlib.config.Registry;
import ch.boye.httpclientandroidlib.config.RegistryBuilder;
import ch.boye.httpclientandroidlib.conn.params.ConnRoutePNames;
import ch.boye.httpclientandroidlib.conn.socket.ConnectionSocketFactory;
import ch.boye.httpclientandroidlib.conn.socket.PlainConnectionSocketFactory;
import ch.boye.httpclientandroidlib.impl.client.DefaultHttpClient;
import ch.boye.httpclientandroidlib.protocol.HttpContext;
import ch.boye.httpclientandroidlib.client.config.RequestConfig;

import com.tinymission.rss.MediaContent;


public class SyncServiceMediaDownloader implements Runnable
{
	public final static boolean LOGGING = false;
	public final static String LOGTAG = "SyncServiceMediaDownloader";

	SyncService syncService;
	SyncService.SyncTask syncTask;
	
	Messenger messenger;
	Handler runHandler;
		
	public SyncServiceMediaDownloader(SyncService _syncService, SyncService.SyncTask _syncTask)
	{
		syncService = _syncService;
		syncTask = _syncTask;
	
		runHandler = new Handler() {
			@Override
			public void handleMessage(Message msg) {
				// Just assuming this means the thread is done
				syncTask.taskComplete(SyncTask.FINISHED);
			}
		};
		
		messenger = new Messenger(runHandler);
	}	

	private void copyFile(File src, File dst) throws IOException
	{
		InputStream in = new FileInputStream(src);
		OutputStream out = new FileOutputStream(dst);

		// Transfer bytes from in to out
		byte[] buf = new byte[1024];
		int len;
		while ((len = in.read(buf)) > 0)
		{
			out.write(buf, 0, len);
		}
		in.close();
		out.close();
	}

	@Override
	public void run() 
	{		
		if (LOGGING)
			Log.v(LOGTAG, "SyncServiceMediaDownloader: run");

		File savedFile = null;
		InputStream inputStream = null;

		MediaContent mediaContent = syncTask.mediaContent;

		//StrongHttpsClient httpClient = new StrongHttpsClient(syncService.getApplicationContext());

		// Replacement
		HttpURLConnection connection = null;

		/*
		if (SocialReader.getInstance(syncService.getApplicationContext()).useTor())
		{
			httpClient.useProxy(true, SocialReader.PROXY_TYPE, SocialReader.PROXY_HOST, SocialReader.PROXY_PORT);
			
			if (LOGGING)
				Log.v(LOGTAG, "MediaDownloader: USE_TOR");
		}
		*/
		
		if (mediaContent.getUrl() != null && !(mediaContent.getUrl().isEmpty()))
		{
			try
			{

			// Replacement
			if (SocialReader.getInstance(syncService.getApplicationContext()).useTor())
			{
				java.net.Proxy proxy = new java.net.Proxy(java.net.Proxy.Type.HTTP, new InetSocketAddress("127.0.0.1", 8118));
				if (mediaContent.getUrl().startsWith("https")) {
					connection = (HttpsURLConnection) new URL(mediaContent.getUrl()).openConnection(proxy);
				} else {
					connection = (HttpURLConnection) new URL(mediaContent.getUrl()).openConnection(proxy);
				}
			}
			else {
				if (mediaContent.getUrl().startsWith("https")) {
					connection = (HttpsURLConnection) new URL(mediaContent.getUrl()).openConnection();
				} else {
					connection = (HttpURLConnection) new URL(mediaContent.getUrl()).openConnection();
				}
			}
			// Replacement
			
				File possibleFile = new File(SocialReader.getInstance(syncService.getApplicationContext()).getFileSystemDir(), SocialReader.MEDIA_CONTENT_FILE_PREFIX + mediaContent.getDatabaseId());
				if (possibleFile.exists())
				{
					if (LOGGING)
						Log.v(LOGTAG, "Image already downloaded: " + possibleFile.getAbsolutePath());
				}
				else if (mediaContent.getUrl().startsWith("file:///"))
				{
					if (LOGGING)
						Log.v(LOGTAG, "Have a file:/// url");
					URI existingFileUri = new URI(mediaContent.getUrl());

					File existingFile = new File(existingFileUri);

					//savedFile = new File(((App)syncService.getApplication()).socialReader.getFileSystemCache(), mediaContent.getDatabaseId() + ".jpg");
					savedFile = new File(SocialReader.getInstance(syncService.getApplicationContext()).getFileSystemDir(), SocialReader.MEDIA_CONTENT_FILE_PREFIX + mediaContent.getDatabaseId());
					copyFile(existingFile, savedFile);
					if (LOGGING)
						Log.v(LOGTAG, "Copy should have worked: " + savedFile.getAbsolutePath());
				}
				else 
				{
					
					//HttpGet httpGet = new HttpGet(mediaContent.getUrl());
					//HttpResponse response = httpClient.execute(httpGet);
					inputStream = connection.getInputStream();

					/*
					int statusCode = response.getStatusLine().getStatusCode();
					if (statusCode != HttpStatus.SC_OK)
					{
						if (LOGGING)
							Log.w(LOGTAG, "Error " + statusCode + " while retrieving bitmap");
					} 
					else 
					{	
						HttpEntity entity = response.getEntity();
						if (entity == null)
						{
							if (LOGGING)
								Log.v(LOGTAG, "MediaDownloader: no response");
						}
						else 
						{
							if (mediaContent.getType() != null) { 
								if (LOGGING)
									Log.v(LOGTAG, "MediaDownloader: " + mediaContent.getType().toString());
							}
					*/
							savedFile = new File(SocialReader.getInstance(syncService.getApplicationContext()).getFileSystemDir(), SocialReader.MEDIA_CONTENT_FILE_PREFIX + mediaContent.getDatabaseId());

							BufferedOutputStream bos = new BufferedOutputStream(new FileOutputStream(savedFile));
					//		inputStream = entity.getContent();
					//		long size = entity.getContentLength();

							byte data[] = new byte[1024];
							int count;
							long total = 0;
							while ((count = inputStream.read(data)) != -1)
							{
								total += count;
								bos.write(data, 0, count);
								//publishProgress((int) (total / size * 100));
							}

							inputStream.close();
							bos.close();
					//		entity.consumeContent();
							
					//		mediaContent.setFileSize(size);
							mediaContent.setFileSize(savedFile.length());
							mediaContent.setDownloaded(true);
					//	}
					//}
				}
				SocialReader sr = SocialReader.getInstance(syncService.getApplicationContext());
				// Should make sure this an image before calling getStoreBitmapDimensions
				sr.getStoreBitmapDimensions(mediaContent);
				sr.setMediaContentDownloaded(mediaContent);
			}
			catch (ClientProtocolException e)
			{
				// TODO Auto-generated catch block
				if (LOGGING)
					e.printStackTrace();
			}
			catch (IOException e)
			{
				// TODO Auto-generated catch block
				if (LOGGING)
					e.printStackTrace();
			}
			catch (URISyntaxException e)
			{
				// TODO Auto-generated catch block
				if (LOGGING)
					e.printStackTrace();
			}
		}

		// Go back to the main thread
		Message m = Message.obtain();            
        //Bundle b = new Bundle();
        //b.putLong("databaseid", feed.getDatabaseId());
		//m.setData(b);
		
        try {
			messenger.send(m);
		} catch (RemoteException e) {
			if (LOGGING)
				e.printStackTrace();
		}
	}
}