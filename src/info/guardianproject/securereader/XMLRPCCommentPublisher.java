package info.guardianproject.securereader;

import info.guardianproject.iocipher.File;
import info.guardianproject.onionkit.trust.StrongHttpsClient;

import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

import net.bican.wordpress.Wordpress;
import redstone.xmlrpc.XmlRpcClient;
import redstone.xmlrpc.XmlRpcStruct;
import android.os.AsyncTask;
import android.util.Log;
import ch.boye.httpclientandroidlib.client.HttpClient;

import com.tinymission.rss.Comment;

/**
 * Class for fetching the feed content in the background.
 * 
 */
public class XMLRPCCommentPublisher extends AsyncTask<Comment, Integer, Comment>
{
	public final static boolean LOGGING = true;
	public final static String LOGTAG = "XMLRPC PUBLISHER";

	SocialReporter socialReporter;

	XMLRPCCommentPublisherCallback commentPublishedCallback;

	public void setXMLRPCCommentPublisherCallback(XMLRPCCommentPublisherCallback _commentPublishedCallback)
	{
		commentPublishedCallback = _commentPublishedCallback;
	}

	public interface XMLRPCCommentPublisherCallback
	{
		public static final int FAILURE_REASON_NO_PRIVACY_PROXY = 1;
		public static final int FAILURE_REASON_NO_CONNECTION = 2;
		
		
		public void commentPublished(Comment _comment);
		public void commentPublishFailure(int reason);
	}

	public XMLRPCCommentPublisher(SocialReporter _socialReporter)
	{
		super();
		socialReporter = _socialReporter;
	}

	@Override
	protected Comment doInBackground(Comment... params)
	{
		Comment comment = new Comment();
		if (params.length == 0)
		{
			if (LOGGING)
				Log.v(LOGTAG, "doInBackground params length is 0, no comment to publish ");
		}
		else
		{
			comment = params[0];

			try
			{
				XmlRpcClient.setContext(socialReporter.applicationContext);

				//TODO - PROXY WORK
//				if (socialReporter.useTor() && socialReporter.socialReader.isTorOnline())
//				{
//					XmlRpcClient.setProxy(true, SocialReader.PROXY_TYPE, SocialReader.PROXY_HOST, SocialReader.PROXY_PORT);
//				}
//				else if (socialReporter.useTor() && !socialReporter.socialReader.isTorOnline()) {
//					// Indicate failure somehow
//					return comment;
//				}
//				else
				{
					XmlRpcClient.setProxy(false, null, null, -1);
				}

				String xmlRPCUsername = socialReporter.socialReader.ssettings.getXMLRPCUsername();
				String xmlRPCPassword = socialReporter.socialReader.ssettings.getXMLRPCPassword();
								
				if (xmlRPCUsername == null || xmlRPCPassword == null) {
					
					String nickname = socialReporter.socialReader.ssettings.nickname();
					if (nickname == null) {
						nickname = xmlRPCUsername;
					}
					
					// acxu.createUser
					ArrayList arguments = new ArrayList();
					arguments.add(nickname);
					XmlRpcClient xpc = new XmlRpcClient(new URL(socialReporter.xmlrpcEndpoint));
					String result = (String) xpc.invoke("acxu.createUser", arguments);
					if (LOGGING) 
						Log.v(LOGTAG,"From wordpress: " + result);
					String[] sresult = result.split(" ");
					if (sresult.length == 2) {
						xmlRPCUsername = sresult[0];
						xmlRPCPassword = sresult[1];
						
						socialReporter.socialReader.ssettings.setXMLRPCUsername(xmlRPCUsername);
						socialReporter.socialReader.ssettings.setXMLRPCPassword(xmlRPCPassword);
					}
				}
				
				if (xmlRPCUsername != null && xmlRPCPassword != null) 
				{
	
					if (LOGGING) 
						Log.v(LOGTAG, "Logging into XMLRPC Interface: " + xmlRPCUsername + '@' + socialReporter.xmlrpcEndpoint);
					Wordpress wordpress = new Wordpress(xmlRPCUsername, xmlRPCPassword, socialReporter.xmlrpcEndpoint);
	
					String nickname = socialReporter.socialReader.ssettings.nickname();
					if (nickname == null) {
						nickname = xmlRPCUsername;
					}
					
					Integer comment_parent = null;
					String content = comment.getDescription();
					String author = nickname;
					String author_url = "";
					String author_email = "";
					Log.v(LOGTAG, "Remote Post ID: " + socialReporter.socialReader.getItemFromId(comment.getItemId()).getRemotePostId());
					Integer post_id = Integer.valueOf(socialReporter.socialReader.getItemFromId(comment.getItemId()).getRemotePostId());
					
					Integer commentId = wordpress.newComment(post_id, comment_parent, content, author, author_url, author_email);
					
					if (LOGGING)
						Log.v(LOGTAG, "Posted: " + commentId);
				} else {
					if (LOGGING)
						Log.e(LOGTAG,"Can't publish, no username/password");
				}

			
			}
			catch (MalformedURLException e)
			{
				if (LOGGING)
					e.printStackTrace();
			}
			catch (Exception e)
			{
				if (LOGGING)
					e.printStackTrace();
			}
		}
		return comment;
	}

	@Override
	protected void onPostExecute(Comment comment)
	{
		if (commentPublishedCallback != null)
		{
			commentPublishedCallback.commentPublished(comment);
		}
	}
}
