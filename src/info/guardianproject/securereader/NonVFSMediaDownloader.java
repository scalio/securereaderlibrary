package info.guardianproject.securereader;

import info.guardianproject.securereader.StrongHttpsClient;
import info.guardianproject.securereader.MediaDownloader.MediaDownloaderCallback;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.URISyntaxException;

import android.content.ContentResolver;
import android.net.Uri;
import android.os.AsyncTask;
import android.util.Log;
import ch.boye.httpclientandroidlib.HttpEntity;
import ch.boye.httpclientandroidlib.HttpHost;
import ch.boye.httpclientandroidlib.HttpResponse;
import ch.boye.httpclientandroidlib.HttpStatus;
import ch.boye.httpclientandroidlib.client.ClientProtocolException;
import ch.boye.httpclientandroidlib.client.HttpClient;
import ch.boye.httpclientandroidlib.client.methods.HttpGet;
import ch.boye.httpclientandroidlib.conn.params.ConnRoutePNames;

import com.tinymission.rss.MediaContent;

public class NonVFSMediaDownloader extends AsyncTask<MediaContent, Integer, File>
{
	public final static String LOGTAG = "NonVFSMediaDownloader";
	public final static boolean LOGGING = false;
	
	SocialReader socialReader;
	MediaDownloaderCallback callback;
	File savedFile;		

	public NonVFSMediaDownloader(SocialReader _socialReader, File locationToSave)
	{
		super();
		socialReader = _socialReader;
		savedFile = locationToSave;
	}

	public interface MediaDownloaderCallback
	{
		public void mediaDownloaded(java.io.File mediaFile);
	}

	public void setMediaDownloaderCallback(MediaDownloaderCallback mdc)
	{
		callback = mdc;
	}

	@Override
	protected File doInBackground(MediaContent... params)
	{
		if (LOGGING) 
			Log.v(LOGTAG, "doInBackground");

		InputStream inputStream = null;

		if (params.length == 0)
			return null;

		MediaContent mediaContent = params[0];
		StrongHttpsClient httpClient = new StrongHttpsClient(socialReader.applicationContext);

		if (socialReader.useTor())
		{
			httpClient.useProxy(true, SocialReader.PROXY_TYPE, SocialReader.PROXY_HOST, SocialReader.PROXY_PORT);
			
			if (LOGGING)
				Log.v(LOGTAG, "USE_TOR");
		}

		if (mediaContent.getUrl() != null && !(mediaContent.getUrl().isEmpty()))
		{
			try
			{
				Uri uriMedia = Uri.parse(mediaContent.getUrl());

				HttpGet httpGet = new HttpGet(mediaContent.getUrl());
				httpGet.setHeader("User-Agent", SocialReader.USERAGENT);
				
				HttpResponse response = httpClient.execute(httpGet);

				int statusCode = response.getStatusLine().getStatusCode();
				if (statusCode != HttpStatus.SC_OK)
				{
					if (LOGGING)
						Log.w(LOGTAG, "Error " + statusCode + " while retrieving file");
					return null;
				}

				HttpEntity entity = response.getEntity();
				if (entity == null)
				{
					if (LOGGING)
						Log.v(LOGTAG, "MediaDownloader: no response");

					return null;
				}

				if (LOGGING)
					Log.v(LOGTAG, "MediaDownloader: " + mediaContent.getType().toString());

				BufferedOutputStream bos = new BufferedOutputStream(new FileOutputStream(savedFile));
				inputStream = entity.getContent();
				long size = entity.getContentLength();

				byte data[] = new byte[1024];
				int count;
				long total = 0;
				while ((count = inputStream.read(data)) != -1)
				{
					total += count;
					bos.write(data, 0, count);
					publishProgress((int) (total / size * 100));
				}

				inputStream.close();
				bos.close();
				entity.consumeContent();

			}
			catch (ClientProtocolException e)
			{
				if (LOGGING)
					e.printStackTrace();
			}
			catch (IOException e)
			{
				if (LOGGING)
					e.printStackTrace();
			}
		}

		return savedFile;
	}

	@Override
	protected void onProgressUpdate(Integer... progress)
	{
		//if (LOGGING)
		// Log.v(LOGTAG, progress[0].toString());
	}

	@Override
	protected void onPostExecute(File cachedFile)
	{
		if (callback != null)
		{
			callback.mediaDownloaded(cachedFile);
		}
	}
}
