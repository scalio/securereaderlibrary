/*
 *   This is the main class of the SocialReader portion of the application.
 *   It contains the management of online vs. offline
 *   It manages the database and tor connections
 *   It interfaces with the UI but doesn't contain any of the UI code itself
 *   It is therefore meant to allow the SocialReader to be pluggable with RSS
 *   API and UI and so on
 */

package info.guardianproject.securereader;

//import info.guardianproject.bigbuffalo.adapters.DownloadsAdapter;
import info.guardianproject.cacheword.CacheWordHandler;
import info.guardianproject.cacheword.CacheWordSettings;
import info.guardianproject.cacheword.Constants;
import info.guardianproject.cacheword.ICacheWordSubscriber;
import info.guardianproject.cacheword.IOCipherMountHelper;
import info.guardianproject.iocipher.File;
import info.guardianproject.iocipher.FileInputStream;
import info.guardianproject.iocipher.FileOutputStream;
import info.guardianproject.iocipher.VirtualFileSystem;
import info.guardianproject.onionkit.ui.OrbotHelper;
import info.guardianproject.securereader.HTMLRSSFeedFinder.RSSFeed;
import info.guardianproject.securereader.MediaDownloader.MediaDownloaderCallback;
import info.guardianproject.securereader.Settings.UiLanguage;
import info.guardianproject.securereader.SyncServiceFeedFetcher.SyncServiceFeedFetchedCallback;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.ObjectOutputStream;
import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.Date;
import java.util.Timer;
import java.util.TimerTask;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import android.annotation.SuppressLint;
import android.annotation.TargetApi;
import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.res.Resources;
import android.graphics.BitmapFactory;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.os.StatFs;
import android.support.v4.content.LocalBroadcastManager;
import android.util.Log;

import com.tinymission.rss.Feed;
import com.tinymission.rss.Item;
import com.tinymission.rss.MediaContent;
import com.tinymission.rss.MediaContent.MediaContentType;

public class SocialReader implements ICacheWordSubscriber
{
	public interface SocialReaderLockListener
	{
		void onLocked();
		void onUnlocked();
	}
	
	// Change this when building for release
	public static final boolean TESTING = false;
	
	public static final String LOGTAG = "SocialReader";
	public static final boolean LOGGING = false;
	
	public static final boolean REPORT_METRICS = true;
	
	public static final String CONTENT_SHARING_MIME_TYPE = "application/x-bigbuffalo-bundle";
	public static final String CONTENT_SHARING_EXTENSION = "bbb";
	public static final String CONTENT_ITEM_EXTENSION = "bbi";
	
	public static final int APP_IN_FOREGROUND = 1;
	public static final int APP_IN_BACKGROUND = 0;
	public int appStatus = 0;

	public static final int FULL_APP_WIPE = 100;
	public static final int DATA_WIPE = 101;

	public final static String PROXY_TYPE = "SOCKS";
	public final static String PROXY_HOST = "127.0.0.1";
	public final static int PROXY_PORT = 9050; // default for SOCKS Orbot/Tor
	
	public final static String PSIPHON_PROXY_HOST = "127.0.0.1";
	public final static String PSIPHON_PROXY_TYPE = "SOCKS";
	public final static int PSIPHON_PROXY_PORT = 1080;
	
	public final static String USERAGENT = "Mozilla/5.0 (Linux; Android 4.0.4; Galaxy Nexus Build/IMM76B) AppleWebKit/535.19 (KHTML, like Gecko) Chrome/18.0.1025.133 Mobile Safari/535.19";
	
	//proxy type tor or psiphon

	public final static boolean RESET_DATABASE = false;

	public static final String MEDIA_CONTENT_FILE_PREFIX = "mc_";
	public static final String CONTENT_BUNDLE_FILE_PREFIX = "bundle_";
	
	public static final String TEMP_ITEM_CONTENT_FILE_NAME = "temp" + "." + CONTENT_ITEM_EXTENSION;
	
	public static final String VFS_SHARE_DIRECTORY = "share";
	public static final String NON_VFS_SHARE_DIRECTORY = "share";

	public final static String FILES_DIR_NAME = "bbfiles";
	public final static String IOCIPHER_FILE_NAME = "vfs.db";

	public static String[] EXTERNAL_STORAGE_POSSIBLE_LOCATIONS = {"/sdcard/external_sdcard", "/sdcard/ext_sd", "/externalSdCard", "/extSdCard", "/external"};

	private String ioCipherFilePath;
	private VirtualFileSystem vfs;

	public static final int DEFAULT_NUM_FEED_ITEMS = 20;
	
	public static final int MEDIA_ITEM_DOWNLOAD_LIMIT_PER_FEED_PER_SESSION = 5;
	//mediaItemDownloadLimitPerFeedPerSession

	public long defaultFeedId = -1;
	public final int feedRefreshAge;
	public final int expirationCheckFrequency;
	public final int opmlCheckFrequency;
	public final String opmlUrl;
	
	public static final int TIMER_PERIOD = 30000;  // 30 seconds 
	
	public final int itemLimit;
	public final int mediaCacheSize;
	public final long mediaCacheSizeLimitInBytes;
	
	// Constant to use when passing an item to be shared to the
	// securebluetoothsender as an extra in the intent
	public static final String SHARE_ITEM_ID = "SHARE_ITEM_ID";

	public Context applicationContext;
	DatabaseAdapter databaseAdapter;
	CacheWordHandler cacheWord;
	CacheWordSettings cacheWordSettings;
	public SecureSettings ssettings;
	Settings settings;
	SyncServiceConnection syncServiceConnection;
	OrbotHelper oc;
	SocialReaderLockListener lockListener;
	
	public static final int ONLINE = 1;
	public static final int NOT_ONLINE_NO_TOR = -1;
	public static final int NOT_ONLINE_NO_WIFI = -2;
	public static final int NOT_ONLINE_NO_WIFI_OR_NETWORK = -3;

	private SocialReader(Context _context) {
		
		this.applicationContext = _context;
		
		feedRefreshAge = applicationContext.getResources().getInteger(R.integer.feed_refresh_age);
		expirationCheckFrequency = applicationContext.getResources().getInteger(R.integer.expiration_check_frequency);
		opmlCheckFrequency = applicationContext.getResources().getInteger(R.integer.opml_check_frequency);
		opmlUrl = applicationContext.getResources().getString(R.string.opml_url);
		
		itemLimit = applicationContext.getResources().getInteger(R.integer.item_limit);
		mediaCacheSize = applicationContext.getResources().getInteger(R.integer.media_cache_size);
		mediaCacheSizeLimitInBytes = mediaCacheSize * 1000 * 1000;
		
		this.settings = new Settings(applicationContext);
		
		this.cacheWordSettings = new CacheWordSettings(applicationContext);
		this.cacheWord = new CacheWordHandler(applicationContext, this, cacheWordSettings);
		cacheWord.connectToService();
		
		this.oc = new OrbotHelper(applicationContext);
		
		
		LocalBroadcastManager.getInstance(_context).registerReceiver(
				new BroadcastReceiver() {
			        @Override
			        public void onReceive(Context context, Intent intent) {
			            if (intent.getAction().equals(Constants.INTENT_NEW_SECRETS)) {
			            	// Locked because of timeout
			            	if (initialized && cacheWord.getCachedSecrets() == null)
			            		SocialReader.this.onCacheWordLocked();
			            }
			        }
			    }, new IntentFilter(Constants.INTENT_NEW_SECRETS));
		}
	
    private static SocialReader socialReader = null;
    public static SocialReader getInstance(Context _context) {
    	if (socialReader == null) {
    		socialReader = new SocialReader(_context);
    	}
    	return socialReader;
    }

	Timer periodicTimer;
	TimerTask periodicTask;
	
	class TimerHandler extends Handler {
        @Override
        public void dispatchMessage(Message msg) {
        	
        	if (LOGGING)
        		Log.v(LOGTAG,"Timer Expired");

    		if (settings.syncFrequency() != Settings.SyncFrequency.Manual) {
    			
    			if (LOGGING)
    				Log.v(LOGTAG, "Sync Frequency not manual");
    			
    			if ((appStatus == SocialReader.APP_IN_BACKGROUND && settings.syncFrequency() == Settings.SyncFrequency.InBackground)
    				|| appStatus == SocialReader.APP_IN_FOREGROUND) {
    				
    				if (LOGGING)
    					Log.v(LOGTAG, "App in background and sync frequency set to in background OR App in foreground");
    				
    	        	checkOPML();
    				backgroundSyncSubscribedFeeds();
    				checkMediaDownloadQueue();
    			} else {
    				if (LOGGING)
    					Log.v(LOGTAG, "App in background and sync frequency not set to background");
    			}
    		} else {
    			if (LOGGING)
    				Log.v(LOGTAG, "Sync Frequency manual, not taking action");
    		}
			expireOldContent();
        }
	}
	TimerHandler timerHandler = new TimerHandler();
	
    private SyncService syncService;
    private SyncService.SyncServiceListener syncServiceListener;

    public SyncService getSyncService()
    {
    	return syncService;
    }
    
    public void setSyncServiceListener(SyncService.SyncServiceListener listener) {
    	syncServiceListener = listener;

    	if (syncService != null) {
    		if (LOGGING)
    			Log.v(LOGTAG,"Setting SyncServiceListener");
    		syncService.setSyncServiceListener(syncServiceListener);
    	} else {
    		if (LOGGING) {
    			Log.v(LOGTAG,"Can't set SyncServiceListener, syncService is null");
    			Log.v(LOGTAG, "No problem, we'll add it later, when we bind");
    		}
    	}
    }

    public void setLockListener(SocialReaderLockListener lockListener)
    {
    	this.lockListener = lockListener;
    }
    
	class SyncServiceConnection implements ServiceConnection {

		public boolean isConnected = false;

        @Override
        public void onServiceConnected(ComponentName className, IBinder service) {
            // This is called when the connection with the service has been
            // established, giving us the service object we can use to
            // interact with the service.  Because we have bound to a explicit
            // service that we know is running in our own process, we can
            // cast its IBinder to a concrete class and directly access it.
        	syncService = ((SyncService.LocalBinder)service).getService();

        	if (LOGGING)
        		Log.v(LOGTAG,"Connected to SyncService");

        	// Add Listener?
        	if (syncServiceListener != null) {
        		syncService.setSyncServiceListener(syncServiceListener);
        		if (LOGGING)
        			Log.v(LOGTAG,"added syncServiceListener");
        	}

    		// Back to the front, check the syncing
    		if (settings.syncFrequency() != Settings.SyncFrequency.Manual) {
    			backgroundSyncSubscribedFeeds();
    		}

    		isConnected = true;
        }

        @Override
        public void onServiceDisconnected(ComponentName className) {
            // This is called when the connection with the service has been
            // unexpectedly disconnected -- that is, its process crashed.
            // Because it is running in our same process, we should never
            // see this happen.

        	syncService = null;

        	if (LOGGING)
        		Log.v(LOGTAG,"Disconnected from SyncService");

        	isConnected = false;
        }
    };

	private boolean initialized = false;
	public void initialize() {
		if (LOGGING)
			Log.v(LOGTAG,"initialize");

	    if (!initialized) {

            initializeFileSystemCache();
            initializeDatabase();
            
            ssettings = new SecureSettings(databaseAdapter);
            if (LOGGING)
            	Log.v(LOGTAG,"SecureSettings initialized");

            syncServiceConnection = new SyncServiceConnection();

            //Using startService() overrides the default service lifetime that is managed by bindService(Intent, ServiceConnection, int): it requires the service to remain running until stopService(Intent) is called, regardless of whether any clients are connected to it. Note that calls to startService() are not nesting: no matter how many times you call startService(), a single call to stopService(Intent) will stop it.
            applicationContext.startService(new Intent(applicationContext, SyncService.class));
            applicationContext.bindService(new Intent(applicationContext, SyncService.class), syncServiceConnection, Context.BIND_AUTO_CREATE);

            periodicTask = new TimerTask() {
                @Override
                public void run() {
                	timerHandler.sendEmptyMessage(0);
                }
            };

            periodicTimer = new Timer();
            periodicTimer.schedule(periodicTask, 0, TIMER_PERIOD);            
            
            initialized = true;
            if (lockListener != null)
            	lockListener.onUnlocked();

	    } else {
	    	if (LOGGING)
	    		Log.v(LOGTAG,"Already initialized!");
	    }
	}

	public void uninitialize() {
		if (syncServiceConnection != null && syncServiceConnection.isConnected) {
			applicationContext.unbindService(syncServiceConnection);
			syncServiceConnection.isConnected = false;
		}

		// If we aren't going to do any background syncing, stop the service
		// Cacheword's locked, we can't do any background work
		//if (settings.syncFrequency() != Settings.SyncFrequency.InBackground)
		//{
		//	if (LOGGING)
		//		Log.v(LOGTAG,"settings.syncFrequency() != Settings.SyncFrequency.InBackground so we are stopping the service");
			applicationContext.stopService(new Intent(applicationContext, SyncService.class));

			if (databaseAdapter != null && databaseAdapter.databaseReady()) {
				if (LOGGING) 
					Log.v(LOGTAG,"database needs closing, doing that now");
	        	databaseAdapter.close();
	        } else {
	        	if (LOGGING) 
					Log.v(LOGTAG,"database doesn't needs closing, strange...");
	        }

			/* unmount is a noop in iocipher 
	        if (vfs != null && vfs.isMounted()) {
	        	if (LOGGING)
	        		Log.v(LOGTAG,"file system mounted, unmounting now");
	        	vfs.unmount();
	        	
	        	for (int i = 0; i < 100; i++) {
	        		if (vfs.isMounted()) {
	        			if (LOGGING)
	        				Log.v(LOGTAG,"file system is still mounted, what's up?!");
	        		} else {
	        			if (LOGGING)
	        				Log.v(LOGTAG,"All is well!");
	        		}
	        	}
	        }
	        else {
	        	if (LOGGING)
	        		Log.v(LOGTAG,"file system not mounted, no need to unmount");
	        }
	        */
		//}
		
		if (periodicTimer != null)
			periodicTimer.cancel();
		
		initialized = false;
        if (lockListener != null)
        	lockListener.onLocked();
	}
	
	public void loadOPMLFile() {
		if (LOGGING)
			Log.v(LOGTAG,"loadOPMLFile()");
		
		logStatus();
		
		if (!settings.localOpmlLoaded()) {
			if (LOGGING)
				Log.v(LOGTAG, "Local OPML Not previously loaded, loading now");
			Resources res = applicationContext.getResources();
			InputStream inputStream = res.openRawResource(R.raw.bigbuffalo_opml);
			
			OPMLParser oParser = new OPMLParser(inputStream,
					new OPMLParser.OPMLParserListener() {
						@Override
						public void opmlParsed(ArrayList<OPMLParser.OPMLOutline> outlines) {
							if (LOGGING)
								Log.v(LOGTAG,"Finished Parsing OPML Feed");
							
							if (outlines != null) {
								for (int i = 0; i < outlines.size(); i++) {
									OPMLParser.OPMLOutline outlineElement = outlines.get(i);
										
									Feed newFeed = new Feed(outlineElement.text, outlineElement.xmlUrl);
									newFeed.setSubscribed(outlineElement.subscribe);
									
									databaseAdapter.addOrUpdateFeed(newFeed);
									if (LOGGING)
										Log.v(LOGTAG,"May have added feed");
								}
							} else {
								if (LOGGING)
									Log.e(LOGTAG,"Received null after OPML Parsed");
							}
							settings.setLocalOpmlLoaded();
							manualSyncSubscribedFeeds(
									new FeedFetcher.FeedFetchedCallback()
									{
										@Override
										public void feedFetched(Feed _feed)
										{
											checkMediaDownloadQueue();
										}
									}
									);
						}
					}
				);
		}
	}
	
	public void feedSubscriptionsChanged() {
		clearMediaDownloadQueue();
		checkMediaDownloadQueue();
	}
	
	private void expireOldContent() {
		if (LOGGING)
			Log.v(LOGTAG,"expireOldContent");
		if (settings.articleExpiration() != Settings.ArticleExpiration.Never) {
			if (settings.lastItemExpirationCheckTime() < System.currentTimeMillis() - expirationCheckFrequency) {
				if (LOGGING)
					Log.v(LOGTAG,"Checking Article Expirations");
				Date expirationDate = new Date(System.currentTimeMillis() - settings.articleExpirationMillis());
				databaseAdapter.deleteExpiredItems(expirationDate);
			}
		} else {
			if (LOGGING)
				Log.v(LOGTAG,"Settings set to never expire");
		}
	}
	
	public void checkForRSSFeed(String url) {
		if (databaseAdapter != null && databaseAdapter.databaseReady() && isOnline() == ONLINE) {
			HTMLRSSFeedFinder htmlParser = new HTMLRSSFeedFinder(SocialReader.this, url,
				new HTMLRSSFeedFinder.HTMLRSSFeedFinderListener() {
					@Override
					public void feedFinderComplete(ArrayList<RSSFeed> rssFeeds) {
						if (LOGGING)
							Log.v(LOGTAG,"Finished Parsing HTML File");
						if (rssFeeds != null) {
							for (int i = 0; i < rssFeeds.size(); i++) {
								Feed newFeed = new Feed(rssFeeds.get(i).title, rssFeeds.get(i).href);
								newFeed.setSubscribed(true);
								databaseAdapter.addOrUpdateFeed(newFeed);
							}
						} else {
							if (LOGGING)
								Log.e(LOGTAG,"Received null after HTML Parsed");
						}	
					}
				}
			);
		} else {
			// Not online
			if (LOGGING)
				Log.v(LOGTAG, "Can't check feed, not online");
		}
	}

	private void checkOPML() {
		if (LOGGING)
			Log.v(LOGTAG,"checkOPML");
		logStatus();
		if (!settings.networkOpmlLoaded() && databaseAdapter != null && databaseAdapter.databaseReady() && !cacheWord.isLocked() && isOnline() == ONLINE && settings.lastOPMLCheckTime() < System.currentTimeMillis() - opmlCheckFrequency) {
			if (LOGGING)
				Log.v(LOGTAG,"Not already loaded from network, attempting to check");
			UiLanguage lang = settings.uiLanguage();
			String finalOpmlUrl = opmlUrl + "?lang=";
						
			if (lang == UiLanguage.Farsi) {
				finalOpmlUrl = finalOpmlUrl + "fa_IR";
			} else if (lang == UiLanguage.English) {
				finalOpmlUrl = finalOpmlUrl + "en_US";
			} else if (lang == UiLanguage.Tibetan) {
				finalOpmlUrl = finalOpmlUrl + "bo_CN";
			} else if (lang == UiLanguage.Chinese) {
				finalOpmlUrl = finalOpmlUrl + "zh_CN";
			} else if (lang == UiLanguage.Russian) {
				finalOpmlUrl = finalOpmlUrl + "ru_RU";
			} else if (lang == UiLanguage.Ukrainian) {
				finalOpmlUrl = finalOpmlUrl + "uk_UA";
			} else if (lang == UiLanguage.Spanish) {
				finalOpmlUrl = finalOpmlUrl + "es";
			} else if (lang == UiLanguage.Japanese) {
				finalOpmlUrl = finalOpmlUrl + "ja";
			} else if (lang == UiLanguage.Norwegian) {
				finalOpmlUrl = finalOpmlUrl + "nb";
			} else if (lang == UiLanguage.Turkish) {
				finalOpmlUrl = finalOpmlUrl + "tr";
			}
			
			if (!settings.networkOpmlLoaded()) {
				finalOpmlUrl += "&first=true";
			}
			
			if (applicationContext.getResources().getBoolean(R.bool.fulltextfeeds)) {
				finalOpmlUrl += "&fulltext=true";
			}
			
			if (REPORT_METRICS) {
				ConnectivityManager connectivityManager = (ConnectivityManager) applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE);
				NetworkInfo networkInfo;

				int connectionType = -1;
				// Check WiFi
				networkInfo = connectivityManager.getNetworkInfo(ConnectivityManager.TYPE_WIFI);
				if (networkInfo != null && networkInfo.isConnected())
				{
					connectionType = 1;
				} 
				else if (settings.syncNetwork() != Settings.SyncNetwork.WifiOnly) 
				{
					// Check any network type
					networkInfo = connectivityManager.getActiveNetworkInfo();
					if (networkInfo != null && networkInfo.isConnected())
					{
						connectionType = 2;
					}
				}
				String connectionTypeParam = "&nct=" + connectionType;
				finalOpmlUrl += connectionTypeParam;
						
				String torTypeParam = "&p=";
				if (settings.requireTor()) {
					if (oc.isOrbotInstalled() && oc.isOrbotRunning()) {
						torTypeParam += "1";
					} else {
						// But this shouldn't actually work
						torTypeParam += "0";
					}
				} else {
					torTypeParam += "0";
				}				
				finalOpmlUrl += torTypeParam;
				
				String apiLevelParam = "&a=" + android.os.Build.VERSION.SDK_INT;
				finalOpmlUrl += apiLevelParam;
				
				try {
					String deviceNameParam = "&dn=" + URLEncoder.encode(android.os.Build.DEVICE, "UTF-8");
					finalOpmlUrl += deviceNameParam;
				} catch (UnsupportedEncodingException e) {
					if (LOGGING)
						e.printStackTrace();
				}
				
				String numFeedsParam = "&nf=" + getSubscribedFeedsList().size();
				finalOpmlUrl += numFeedsParam;
			}
			
			if (TESTING) 
				finalOpmlUrl += "&testing=1";
			
			if (LOGGING)
				Log.v(LOGTAG, "OPML Feed Url: " + finalOpmlUrl);
			
				OPMLParser oParser = new OPMLParser(SocialReader.this, finalOpmlUrl,
					new OPMLParser.OPMLParserListener() {
						@Override
						public void opmlParsed(ArrayList<OPMLParser.OPMLOutline> outlines) {
							if (LOGGING)
								Log.v(LOGTAG,"Finished Parsing OPML Feed");
									
							if (outlines != null) {
								for (int i = 0; i < outlines.size(); i++) {
									OPMLParser.OPMLOutline outlineElement = outlines.get(i);
									
									Feed newFeed = new Feed(outlineElement.text, outlineElement.xmlUrl);
									newFeed.setSubscribed(outlineElement.subscribe);

									if (LOGGING)
										Log.v(LOGTAG, "**New Feed: " + newFeed.getFeedURL() + " " + newFeed.isSubscribed());
									
									databaseAdapter.addOrUpdateFeed(newFeed);
								}
							} else {
								if (LOGGING)
									Log.e(LOGTAG,"Received null after OPML Parsed");
							}
							settings.setNetworkOpmlLoaded();
							backgroundSyncSubscribedFeeds();
						}
					}
				);
			} else {
				if (LOGGING)
					Log.v(LOGTAG,"Not checking OPML at this time");
			}
	}

	// When the foreground app is paused
	public void onPause() {
		if (LOGGING)
			Log.v(LOGTAG, "SocialReader onPause");
		appStatus = SocialReader.APP_IN_BACKGROUND;
		cacheWord.detach();
	}

	// When the foreground app is unpaused
	public void onResume() {
		if (LOGGING)
			Log.v(LOGTAG, "SocialReader onResume");
        appStatus = SocialReader.APP_IN_FOREGROUND;
        cacheWord.reattach();
	}
	
	public void setCacheWordTimeout(int minutes)
	{
		cacheWordSettings.setTimeoutSeconds(minutes*60);
	}
	
	public boolean isTorOnline() 
	{
		if (useTor() && oc.isOrbotInstalled() && oc.isOrbotRunning()) 
		{
			return true;
		} 
		else 
		{
			return false;
		}		
	}
	
	private void logStatus() {
		if (LOGGING)
			Log.v(LOGTAG, "Status Check: ");
		
		if (databaseAdapter != null) {
			if (LOGGING) {
				Log.v(LOGTAG, "databaseAdapter != null");
				Log.v(LOGTAG, "databaseAdapter.databaseReady() " + databaseAdapter.databaseReady());
			}
		} else {
			if (LOGGING)
				Log.v(LOGTAG, "databaseAdapter == null");			
		}
		if (LOGGING) {
			Log.v(LOGTAG, "cacheWord.isLocked() " + cacheWord.isLocked());
			Log.v(LOGTAG, "isOnline() " + isOnline());
		}
	}

	// This public method will indicate whether or not the application is online
	// it takes into account whether or not the application should be online (connectionMode)
	// as well as the physical network connection and tor status
	public int isOnline()
	{
		ConnectivityManager connectivityManager = (ConnectivityManager) applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE);
		NetworkInfo networkInfo;

		if (settings.syncNetwork() == Settings.SyncNetwork.WifiOnly) {
			networkInfo = connectivityManager.getNetworkInfo(ConnectivityManager.TYPE_WIFI);
		} else {
			networkInfo = connectivityManager.getActiveNetworkInfo();
		}

		if (networkInfo != null && networkInfo.isConnectedOrConnecting()) {
			if (settings.requireTor()) {
				if (oc.isOrbotInstalled() && oc.isOrbotRunning()) {
					// Network is connected
					// Tor is running we are good
					return ONLINE;
				} else {
					// Tor not running or not installed
					return NOT_ONLINE_NO_TOR;
				}
			} else {
				// Network is connected and we don't have to use Tor
				return ONLINE;
			}
		} else {
			// Network not connected
			if (settings.syncNetwork() == Settings.SyncNetwork.WifiOnly) {
				return NOT_ONLINE_NO_WIFI;
			}
			else {
				return NOT_ONLINE_NO_WIFI_OR_NETWORK;
			}
		}
	}

	// Working hand in hand with isOnline this tells other classes whether or not they should use Tor when connecting
	public boolean useTor() {
		//if (settings.requireTor() || oc.isOrbotRunning()) {
		if (settings.requireTor()) {
			if (LOGGING)
				Log.v(LOGTAG, "USE TOR");
			return true;
		} else {
			if (LOGGING)
				Log.v(LOGTAG, "DON'T USE TOR");
			return false;
		}
	}

	public boolean connectTor(Activity _activity)
	{
		if (LOGGING) {
			Log.v(LOGTAG, "Checking Tor");
			Log.v(LOGTAG, "isOrbotInstalled: " + oc.isOrbotInstalled());

			// This is returning the wrong value oc.isOrbotRunning, even if Orbot isn't installed
			Log.v(LOGTAG, "isOrbotRunning: " + oc.isOrbotRunning());
		}
		
		if (!oc.isOrbotInstalled())
		{
			// This is getting intercepted by the lock screen at the moment
			oc.promptToInstall(_activity);
		}
		else if (!oc.isOrbotRunning())
		{
			// This seems to be working ok
			oc.requestOrbotStart(_activity);
		}

		return true;
	}
	
	public long getDefaultFeedId() {
		return defaultFeedId;
	}

	/*
	 * Return ArrayList of all Feeds in the database, these feed objects will
	 * not contain item data
	 */
	public ArrayList<Feed> getFeedsList()
	{
		if (databaseAdapter != null && databaseAdapter.databaseReady())
		{
			return databaseAdapter.getAllFeeds();
		}
		else
		{
			return new ArrayList<Feed>();
		}
	}

	/*
	 * Return ArrayList of all Feeds that the user is subscribed to in the
	 * database, these feed objects will not contain item data
	 */
	public ArrayList<Feed> getSubscribedFeedsList()
	{
		if (databaseAdapter != null && databaseAdapter.databaseReady())
		{
			return databaseAdapter.getSubscribedFeeds();
		}
		else
		{
			return new ArrayList<Feed>();
		}
	}

	/*
	 * Return ArrayList of all Feeds in the database that the user is NOT
	 * subscribed to, these feed objects will not contain item data
	 */
	public ArrayList<Feed> getUnsubscibedFeedsList()
	{
		if (databaseAdapter != null && databaseAdapter.databaseReady())
		{
			return databaseAdapter.getUnSubscribedFeeds();
		}
		else
		{
			return new ArrayList<Feed>();
		}
	}

	/*
	 * Utilizes the SyncService to Requests feed and feed items to be pulled from the network
	 */
	private void backgroundRequestFeedNetwork(Feed feed, SyncServiceFeedFetchedCallback callback)
	{
		if (LOGGING)
			Log.v(LOGTAG,"requestFeedNetwork");

		if (syncService != null) {
			if (LOGGING)
				Log.v(LOGTAG,"syncService != null");
			syncService.addFeedSyncTask(feed);
		} else {
			if (LOGGING)
				Log.v(LOGTAG,"syncService is null!");
		}
	}

	/*
	 * Requests feed and feed items to be pulled from the network returns false
	 * if feed cannot be requested from the network
	 */
	private boolean foregroundRequestFeedNetwork(Feed feed, FeedFetcher.FeedFetchedCallback callback)
	{
		FeedFetcher feedFetcher = new FeedFetcher(this);
		feedFetcher.setFeedUpdatedCallback(callback);

		if (isOnline() == ONLINE || feed.getFeedURL().startsWith("file:///"))
		{
			if (LOGGING)
				Log.v(LOGTAG, "Calling feedFetcher.execute: " + feed.getFeedURL());
			feedFetcher.execute(feed);
			return true;
		}
		else
		{
			return false;
		}
	}

	// Do network feed refreshing in the background
	private void backgroundSyncSubscribedFeeds()
	{
		if (LOGGING)
			Log.v(LOGTAG,"backgroundSyncSubscribedFeeds()");

		if (!cacheWord.isLocked()) {
			final ArrayList<Feed> feeds = getSubscribedFeedsList();
			
			if (LOGGING) 
				Log.v(LOGTAG,"Num Subscribed feeds:" + feeds.size());
			
			for (Feed feed : feeds)
			{
				if (LOGGING) 
					Log.v(LOGTAG,"Checking: " + feed.getFeedURL());
				
				if (feed.isSubscribed() && shouldRefresh(feed) && isOnline() == ONLINE) {
					if (LOGGING)
						Log.v(LOGTAG,"It should be refreshed");

					backgroundRequestFeedNetwork(feed, new SyncServiceFeedFetchedCallback() {
						@Override
						public void feedFetched(Feed _feed) {
						}
					});
				} else if (isOnline() != ONLINE) {
					if (LOGGING)
						Log.v(LOGTAG,"not refreshing, not online: " + isOnline());
				} else {
					if (LOGGING)
						Log.v(LOGTAG,"doesn't need refreshing");
				}
			}
		} else {
			if (LOGGING)
				Log.v(LOGTAG, "Can't sync feeds, cacheword locked");
		}
	}
	
	public void clearMediaDownloadQueue() {
		if (LOGGING) 
			Log.v(LOGTAG, "clearMediaDownloadQueue");		
		
		if (!cacheWord.isLocked() && isOnline() == ONLINE && 
				settings.syncMode() != Settings.SyncMode.BitWise
				&& syncService != null) {
				
			syncService.clearSyncList();
			backgroundSyncSubscribedFeeds();
		}
		
	}	

	public void checkMediaDownloadQueue() {
		if (LOGGING) 
			Log.v(LOGTAG, "checkMediaDownloadQueue");		
		
		if (!cacheWord.isLocked() && 
				settings.syncMode() != Settings.SyncMode.BitWise
				&& syncService != null) {
			
			if (LOGGING) 
				Log.v(LOGTAG, "In right state, definitely checkMediaDownloadQueue");
				
			int numWaiting = syncService.getNumWaitingToSync();
			
			if (LOGGING) 
				Log.v(LOGTAG, "Num Waiting TO Sync: " + numWaiting);
			
			if (numWaiting > 0) {
				// Send a no-op to get any going that should be going?
				
			} else {
				
				// Check database for new items to sync
				if (databaseAdapter != null && databaseAdapter.databaseReady())
				{
					// Delete over limit media
					int numDeleted = databaseAdapter.deleteOverLimitMedia(mediaCacheSizeLimitInBytes, this);
					
					if (LOGGING)
						Log.v(LOGTAG,"Deleted " + numDeleted + " over limit media items");
					
					long mediaFileSize = databaseAdapter.mediaFileSize();
					
					if (LOGGING)
						Log.v(LOGTAG,"Media File Size: " + mediaFileSize + " limit is " + mediaCacheSizeLimitInBytes);
					
					if (mediaFileSize < mediaCacheSizeLimitInBytes) {
						
						ArrayList<Item> itemsToDownload = databaseAdapter.getItemsWithMediaNotDownloaded(MEDIA_ITEM_DOWNLOAD_LIMIT_PER_FEED_PER_SESSION);
						if (LOGGING) 
							Log.v(LOGTAG,"Got " + itemsToDownload.size() + " items to download from database");
						
						for (Item item : itemsToDownload)
						{
							ArrayList<MediaContent> mc = item.getMediaContent();
							for (MediaContent m : mc) {
								
								if (LOGGING)
									Log.v(LOGTAG, "Adding to sync " + m.getUrl());
								
								syncService.addMediaContentSyncTask(m);
							}
						}
					}
				}	
			}
		}
		
	}
	
	public boolean manualSyncInProgress() {
		return requestPending;
	}

	// Request all of the feeds one at a time in the foreground
	int requestAllFeedsCurrentFeedIndex = 0;
	Feed compositeFeed = new Feed();

	boolean requestPending = false;
	FeedFetcher.FeedFetchedCallback finalCallback = null;

	public void manualSyncSubscribedFeeds(FeedFetcher.FeedFetchedCallback _finalCallback)
	{
		finalCallback = _finalCallback;

		if (!requestPending)
		{
			requestPending = true;

			final ArrayList<Feed> feeds = getSubscribedFeedsList();

			requestAllFeedsCurrentFeedIndex = 0;
			compositeFeed.clearItems();

			if (LOGGING)
				Log.v(LOGTAG, "requestAllFeedsCurrentFeedIndex:" + requestAllFeedsCurrentFeedIndex);

			if (feeds.size() > 0)
			{
				FeedFetcher.FeedFetchedCallback ffcallback = new FeedFetcher.FeedFetchedCallback()
				{
					@Override
					public void feedFetched(Feed _feed)
					{
						if (LOGGING)
							Log.v(LOGTAG, "Done Fetching: " + _feed.getFeedURL());

						compositeFeed.addItems(_feed.getItems());

						if (requestAllFeedsCurrentFeedIndex < feeds.size() - 1)
						{
							requestAllFeedsCurrentFeedIndex++;
							if (LOGGING)
								Log.v(LOGTAG, "requestAllFeedsCurrentFeedIndex:" + requestAllFeedsCurrentFeedIndex);
							foregroundRequestFeedNetwork(feeds.get(requestAllFeedsCurrentFeedIndex), this);
						}
						else
						{
							if (LOGGING)
								Log.v(LOGTAG, "Feed Fetcher Done!");
							requestPending = false;
							if (finalCallback != null) {
								finalCallback.feedFetched(compositeFeed);
							}
						}
					}
				};

				if (LOGGING)
					Log.v(LOGTAG, "requestAllFeedsCurrentFeedIndex:" + requestAllFeedsCurrentFeedIndex);
				foregroundRequestFeedNetwork(feeds.get(requestAllFeedsCurrentFeedIndex), ffcallback);
			}
			if (LOGGING)
				Log.v(LOGTAG, "feeds.size is " + feeds.size());
		}
	}
	
	/*
	 * This is to manually sync a specific feed. It takes a callback that will
	 * be used to notify the listener that the network process is complete. This
	 * will override the default syncing behavior forcing an immediate network
	 * sync.
	 */
	public void manualSyncFeed(Feed feed, FeedFetcher.FeedFetchedCallback callback)
	{
		if (isOnline() == ONLINE)
		{
			// Adding an intermediate callback
			// Essentially, I never want it directly from the network, I want to
			// re-request it from the database

			final FeedFetcher.FeedFetchedCallback finalcallback = callback;
			FeedFetcher.FeedFetchedCallback intermediateCallback = callback;

			if (databaseAdapter != null && databaseAdapter.databaseReady())
			{
				intermediateCallback = new FeedFetcher.FeedFetchedCallback()
				{
					@Override
					public void feedFetched(Feed _feed)
					{
						if (finalcallback != null) {
							Feed updatedFeed = getFeed(_feed);
							if (appStatus == SocialReader.APP_IN_FOREGROUND) {
								finalcallback.feedFetched(updatedFeed);
							}
						}
					}
				};
			}

			if (LOGGING)
				Log.v(LOGTAG, "Refreshing Feed from Network");
			foregroundRequestFeedNetwork(feed, intermediateCallback);
		}
	}

	/*
	 * This will get a feed's items from the database.
	 */
	public Feed getFeed(Feed feed)
	{
		if (databaseAdapter != null && databaseAdapter.databaseReady())
		{
			if (LOGGING)
				Log.v(LOGTAG, "Feed from Database");
			feed = databaseAdapter.getFeedItems(feed, DEFAULT_NUM_FEED_ITEMS);
		}
		return feed;
	}

	Feed manualCompositeFeed = new Feed();

	/*
	public Feed getSubscribedFeedItems()
	{
		Feed returnFeed = new Feed();
		ArrayList<Feed> feeds = getSubscribedFeedsList();

		for (Feed feed : feeds)
		{
			returnFeed.addItems(getFeed(feed).getItems());
		}

		return returnFeed;
	}
	*/
	
	public Feed getSubscribedFeedItems()
	{
		Feed returnFeed = new Feed();
		if (databaseAdapter != null && databaseAdapter.databaseReady())
		{
			try
			{
				returnFeed = databaseAdapter.getSubscribedFeedItems(DEFAULT_NUM_FEED_ITEMS);
			}
			catch(IllegalStateException e)
			{
				e.printStackTrace();
			}
		}
		return returnFeed;
	}
		
	public Feed getFeedItemsWithTag(Feed feed, String tag) {
		Feed returnFeed = new Feed();
		if (databaseAdapter != null && databaseAdapter.databaseReady())
		{
			returnFeed.addItems(databaseAdapter.getFeedItemsWithTag(feed, tag));
		}
		return returnFeed;
	}

	public Feed getPlaylist(Feed feed) {		
		
		ArrayList<String> tags = new ArrayList<String>();
		// Tempo
		tags.add("slow"); // -1
		tags.add("medium"); // 0
		tags.add("fast"); // +1
		
		// Whatever
		tags.add("nothing");
		tags.add("thing");
		tags.add("something");
		
		return getFeedItemsWithMediaTags(feed, tags, "audio", true, 20);
	}
	
	public Feed getFeedItemsWithMediaTags(Feed feed, ArrayList<String> tags, String mediaMimeType, boolean randomize, int limit) {
		Feed returnFeed = new Feed();
		if (databaseAdapter != null && databaseAdapter.databaseReady())
		{
			returnFeed.addItems(databaseAdapter.getFeedItemsWithMediaTags(feed, tags, mediaMimeType, randomize, limit));
		}
		return returnFeed;
	}	
	
	private void initializeDatabase()
	{
		if (LOGGING)
			Log.v(LOGTAG,"initializeDatabase()");

		if (RESET_DATABASE) {
			applicationContext.deleteDatabase(DatabaseHelper.DATABASE_NAME);
		}

		databaseAdapter = new DatabaseAdapter(cacheWord, applicationContext);

		if (databaseAdapter.getAllFeeds().size() == 0) {
			
			// How come I can't put an array of objects in the XML?
			// You can, sort of: http://stackoverflow.com/questions/4326037/android-resource-array-of-arrays
			String[] builtInFeedNames = applicationContext.getResources().getStringArray(R.array.built_in_feed_names);
			String[] builtInFeedUrls = applicationContext.getResources().getStringArray(R.array.built_in_feed_urls);
			
			if (builtInFeedNames.length == builtInFeedUrls.length) {
				for (int i = 0; i < builtInFeedNames.length; i++) {
					Feed newFeed = new Feed(builtInFeedNames[i], builtInFeedUrls[i]);
					newFeed.setSubscribed(true);
					databaseAdapter.addOrUpdateFeed(newFeed);
				}
			}
						
			loadOPMLFile();
		} else {
			if (LOGGING)
				Log.v(LOGTAG,"Database not empty, not inserting default feeds");
		}

		if (LOGGING)
			Log.v(LOGTAG,"databaseAdapter initialized");
	}

	private boolean testExternalStorage(java.io.File dirToTest) {
		if (LOGGING) 
			Log.v(LOGTAG, "testExternalStorage: " + dirToTest);
		if (dirToTest.exists() && dirToTest.isDirectory()) {
			try {
				java.io.File.createTempFile("test", null, dirToTest);
				
				if (LOGGING) 
					Log.v(LOGTAG, "testExternalStorage: " + dirToTest + " is good");

				return true;
			} catch (IOException ioe) {
				
				if (LOGGING) 
					Log.v(LOGTAG, "testExternalStorage: " + dirToTest + " is NOT good");
				
				return false;
			}
		}
		
		if (LOGGING) 
			Log.v(LOGTAG, "testExternalStorage: " + dirToTest + " is NOT good");
		
		return false;
	}
		
	@SuppressLint("NewApi")
	private java.io.File getNonVirtualFileSystemDir()
	{
		java.io.File filesDir = null;

		boolean done = false;
	    
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
			if (LOGGING) {
				Log.v(LOGTAG, "Running on KITKAT or greater");
			}
			java.io.File[] possibleLocations = applicationContext.getExternalFilesDirs(null);
			long largestSize = 0;
			for (int l = 0; l < possibleLocations.length; l++) {
				if (possibleLocations[l] != null && possibleLocations[l].getAbsolutePath() != null) {	
					long curSize = new StatFs(possibleLocations[l].getAbsolutePath()).getTotalBytes();
					if (LOGGING) {
						Log.v(LOGTAG, "Checking " + possibleLocations[l].getAbsolutePath() + " size: " + curSize);
					}
					if (curSize > largestSize) {
						largestSize = curSize;
						filesDir = possibleLocations[l];
						done = true;
						
						if (LOGGING) {
							Log.v(LOGTAG, "using it");
						}
					}
				}
			}
		} else {
			if (LOGGING) {
				Log.v(LOGTAG, "Below kitkat, checking other SDCard Locations");
			}
			
			for (int p = 0; p < EXTERNAL_STORAGE_POSSIBLE_LOCATIONS.length; p++) {
				if (testExternalStorage(new java.io.File(EXTERNAL_STORAGE_POSSIBLE_LOCATIONS[p]))) {
					filesDir = new java.io.File(EXTERNAL_STORAGE_POSSIBLE_LOCATIONS[p] + "/" + FILES_DIR_NAME);
					if (!filesDir.exists())
					{
						filesDir.mkdirs();
					}
					done = true;
					break;
				}					
			}	    	
	    }
	    

		
		if (!done) {
			if (Environment.getExternalStorageState().equals(Environment.MEDIA_MOUNTED))
			{
				if (LOGGING) 
					Log.v(LOGTAG,"sdcard mounted");
				
				filesDir = applicationContext.getExternalFilesDir(null);
				if (!filesDir.exists())
				{
					filesDir.mkdirs();
				}
				
				if (LOGGING) 
					Log.v(LOGTAG,"filesDir:" + filesDir.getAbsolutePath());

			}
			else
			{
				if (LOGGING) 
					Log.v(LOGTAG,"on internal storage");
				
				filesDir = applicationContext.getDir(FILES_DIR_NAME, Context.MODE_PRIVATE);
			}
		}
	
		return filesDir;
	}
	
	
	private java.io.File getNonVirtualFileSystemInternalDir()
	{
		java.io.File filesDir;

		// Slightly more secure?
		filesDir = applicationContext.getDir(FILES_DIR_NAME, Context.MODE_PRIVATE);
		
		return filesDir;
	}
	
	private void initializeFileSystemCache()
	{
		if (LOGGING)
			Log.v(LOGTAG,"initializeFileSystemCache");

		java.io.File filesDir = getNonVirtualFileSystemDir();

		ioCipherFilePath = filesDir.getAbsolutePath() + "/" + IOCIPHER_FILE_NAME;
		
		if (LOGGING)
			Log.v(LOGTAG, "Creating ioCipher at: " + ioCipherFilePath);
		
		IOCipherMountHelper ioHelper = new IOCipherMountHelper(cacheWord);
		try {
			if (vfs == null) {
				vfs = ioHelper.mount(ioCipherFilePath);
			}
		} catch ( IOException e ) {
			if (LOGGING) {
				Log.e(LOGTAG,"IOCipher open failure");
				e.printStackTrace();
			}
			
			java.io.File existingVFS = new java.io.File(ioCipherFilePath);
			
			if (existingVFS.exists()) {
				existingVFS.delete();

				if (LOGGING) {
					Log.v(LOGTAG,"Deleted existing VFS " + ioCipherFilePath);
				}
			}
		
			try {
				ioHelper = new IOCipherMountHelper(cacheWord);
				vfs = ioHelper.mount(ioCipherFilePath);
			} catch (IOException e1) {
				if (LOGGING) {
					Log.e(LOGTAG, "Still didn't work, IOCipher open failure, giving up");
					//e1.printStackTrace();
				}
			}			
		}

		// Test it
		/*
		File testFile = new File(getFileSystemDir(),"test.txt");
		try {
	        BufferedWriter out = new BufferedWriter(new FileWriter(testFile));
	        out.write("test");
	        out.close();
		} catch (IOException e) {
			Log.e(LOGTAG,"FAILED TEST");			
		}
		*/
		if (LOGGING)
			Log.v(LOGTAG,"***Filesystem Initialized***");
	}

	private void deleteFileSystem()
	{
		if (vfs != null && vfs.isMounted()) {
			vfs.unmount();
			vfs = null;
		}

		// Delete all possible locations
		
		// This will use the removeable external if it is there
		// otherwise it will return the external sd card
		// otherwise it will do internal
		java.io.File possibleDir = getNonVirtualFileSystemDir();
		if (possibleDir.exists()) {
			java.io.File[] possibleDirFiles = possibleDir.listFiles();
			for (int i = 0; i < possibleDirFiles.length; i++)
			{
				possibleDirFiles[i].delete();
			}
			possibleDir.delete();	
		}
		
		// This is a backup, just in case they have a removable sd card inserted but also have
		// files on normal storage
		if (Environment.getExternalStorageState().equals(Environment.MEDIA_MOUNTED))
		{
			// getExternalFilesDir() These persist
			java.io.File externalFilesDir = applicationContext.getExternalFilesDir(null);
			if (externalFilesDir != null && externalFilesDir.exists())
			{
				java.io.File[] externalFiles = externalFilesDir.listFiles();
				for (int i = 0; i < externalFiles.length; i++)
				{
					externalFiles[i].delete();
				}
				externalFilesDir.delete();
			}
		}

		// Final backup, remove from internal storage
		java.io.File internalDir = applicationContext.getDir(FILES_DIR_NAME, Context.MODE_PRIVATE);
		java.io.File[] internalFiles = internalDir.listFiles();
		for (int i = 0; i < internalFiles.length; i++)
		{
			internalFiles[i].delete();
		}
		internalDir.delete();
	}

	public File getFileSystemDir()
	{
		// returns the root of the VFS
		return new File("/");
	}
	
	/*
	 * Checks to see if a feed should be refreshed from the network or not based
	 * upon the the last sync date/time
	 */
	public boolean shouldRefresh(Feed feed)
	{
		long refreshDate = new Date().getTime() - feedRefreshAge;

		if (LOGGING)
			Log.v(LOGTAG, "Feed Databae Id " + feed.getDatabaseId());
		feed = databaseAdapter.fillFeedObject(feed);

		if (feed.getNetworkPullDate() != null)
		{
			if (LOGGING)
				Log.v(LOGTAG, "Feed pull date: " + feed.getNetworkPullDate().getTime());
		}
		else
		{
			if (LOGGING)
				Log.v(LOGTAG, "Feed pull date: NULL");
		}
		if (LOGGING)
			Log.v(LOGTAG, "Feed refresh date: " + refreshDate);

		if (feed.getNetworkPullDate() == null || feed.getNetworkPullDate().getTime() < refreshDate)
		{
			if (LOGGING)
				Log.v(LOGTAG, "Should refresh feed");
			return true;
		}
		else
		{
			if (LOGGING)
				Log.v(LOGTAG, "Get feeed from database");
			return false;
		}
	}

	public String getFeedTitle(long feedId) {
		if (databaseAdapter != null && databaseAdapter.databaseReady())
		{
			return databaseAdapter.getFeedTitle(feedId);
		}
		return "";
	}
	
	/*
	 * Returns feed/list of favorite items for a specific feed
	 */
	public Feed getFeedFavorites(Feed feed)
	{
		if (databaseAdapter != null && databaseAdapter.databaseReady())
		{
			Feed favorites = databaseAdapter.getFavoriteFeedItems(feed);
			return favorites;
		}
		else
		{
			return new Feed();
		}
	}

	public Feed getAllShared() 
	{
		if (databaseAdapter != null && databaseAdapter.databaseReady())
		{
			return databaseAdapter.getAllSharedItems();
		}
		else
		{
			return new Feed();
		}
	}

	/**
	 * Get number of received items.
	 *
	 * @return Number of items received.
	 */
	public int getAllSharedCount()
	{
		return getAllShared().getItemCount();
	}
	
	/*
	 * Returns ArrayList of Feeds containing only the favorites
	 */
	public Feed getAllFavorites()
	{		
		if (databaseAdapter != null && databaseAdapter.databaseReady())
		{
			return databaseAdapter.getAllFavoriteItems(); 
		}
		else
		{
			return new Feed();
		}
	}

	/**
	 * Get number of favorite items.
	 *
	 * @return Number of items marked as favorite.
	 */
	public int getAllFavoritesCount()
	{
		return getAllFavorites().getItemCount();
	}

	public void markItemAsFavorite(Item item, boolean favorite)
	{
		if (databaseAdapter != null && databaseAdapter.databaseReady())
		{
			// Pass in the item that will be marked as a favorite
			// Take a boolean so we can "unmark" a favorite as well.
			item.setFavorite(favorite);
			setItemData(item);
		}
		else
		{
			if (LOGGING)
				Log.e(LOGTAG,"Database not ready: markItemAsFavorite");
		}
	}

	public void addToItemViewCount(Item item) {
		
		if (databaseAdapter != null && databaseAdapter.databaseReady())
		{
			// Pass in the item that will be marked as a favorite
			// Take a boolean so we can "unmark" a favorite as well.
			item.incrementViewCount();
			setItemData(item);
		}
		else
		{
			if (LOGGING)
				Log.e(LOGTAG,"Database not ready: addToItemViewCount");
		}		
	}
	
	public void setMediaContentDownloaded(MediaContent mc) {
		mc.setDownloaded(true);
		if (databaseAdapter != null && databaseAdapter.databaseReady()) {
			databaseAdapter.updateItemMedia(mc);
			//databaseAdapter.deleteOverLimitMedia(mediaCacheSizeLimitInBytes, this);
		}
	}
	
	public void unsetMediaContentDownloaded(MediaContent mc) {
		if (LOGGING)
			Log.v(LOGTAG, "unsetMediaContentDownloaded");
		mc.setDownloaded(false);
		if (databaseAdapter != null && databaseAdapter.databaseReady()) {
			databaseAdapter.updateItemMedia(mc);
		} else {
			if (LOGGING)	
				Log.v(LOGTAG, "Can't update database, not ready");
		}
	}
	
	public long setItemData(Item item)
	{
		if (databaseAdapter != null && databaseAdapter.databaseReady())
		{
			return databaseAdapter.addOrUpdateItem(item, itemLimit);
		}
		else
		{
			if (LOGGING)
				Log.e(LOGTAG,"Database not ready: setItemData");
		}
		return -1;
	}

	/*
	 * Updates the feed data matching the feed object in the database. This
	 * ignores any items that are referenced in the feed object
	 *
	 * Returns null if update failed
	 */
	public Feed setFeedData(Feed feed)
	{
		if (databaseAdapter != null && databaseAdapter.databaseReady())
		{
			// First see if the feed has a valid database ID, if not, add a stub
			// and set the id
			if (feed.getDatabaseId() == Feed.DEFAULT_DATABASE_ID)
			{
				feed.setDatabaseId(databaseAdapter.addFeedIfNotExisting(feed.getTitle(), feed.getFeedURL()));
			}

			// Now update the record in the database, this fills more of the
			// data out
			int result = databaseAdapter.updateFeed(feed);
			if (LOGGING)
				Log.v(LOGTAG, "setFeedData: " + result);

			if (result == 1)
			{
				// Return the feed as it may have a new database id
				return feed;
			}
			else
			{
				return null;
			}
		}
		else
		{
			if (LOGGING)
				Log.e(LOGTAG,"Database not ready: setFeedData");
			return null;
		}
	}

	/*
	 * Updates the feed data matching the feed object in the database. This
	 * includes any items that are referenced in the feed object.
	 */
	public void setFeedAndItemData(Feed feed)
	{
		if (databaseAdapter != null && databaseAdapter.databaseReady())
		{
			setFeedData(feed);

			for (Item item : feed.getItems())
			{
				// Make sure the feed ID is correct and the source is set correctly
				item.setFeedId(feed.getDatabaseId());
				item.setSource(feed.getTitle());
				setItemData(item);
			}
		}
		else
		{
			if (LOGGING)
				Log.e(LOGTAG,"Database not ready: setFeedAndItemData");
		}
	}

	public void backgroundDownloadFeedItemMedia(Feed feed)
	{
		int count = 0;
		feed = getFeed(feed);
		for (Item item : feed.getItems())
		{
			if (count >= MEDIA_ITEM_DOWNLOAD_LIMIT_PER_FEED_PER_SESSION) {
				
				if (LOGGING)
					Log.v(LOGTAG, "!!! " + count + " above limit of " + MEDIA_ITEM_DOWNLOAD_LIMIT_PER_FEED_PER_SESSION);
				
				break;
			}
							
				if (LOGGING)
					Log.v(LOGTAG, "Adding " + count + " media item to background feed download");
				
				backgroundDownloadItemMedia(item);
				count++;
		}
	}
	
	
	public void backgroundDownloadItemMedia(Item item)
	{
		if (settings.syncMode() != Settings.SyncMode.BitWise) {
			for (MediaContent contentItem : item.getMediaContent())
			{
				if (syncService != null) {
					if (LOGGING)
						Log.v(LOGTAG,"syncService != null");
					syncService.addMediaContentSyncTask(contentItem);
				} else {
					if (LOGGING)
						Log.v(LOGTAG,"syncService is null!");
				}
			}
		}
	}

	/*
	 * Adds a new feed to the database, this is used when the user manually
	 * subscribes to a feed
	 */
	public void addFeedByURL(String url, FeedFetcher.FeedFetchedCallback callback)
	{
		//checkForRSSFeed(url);
		
		
		if (databaseAdapter != null && databaseAdapter.databaseReady())
		{
			
			Feed newFeed = new Feed("", url);
			newFeed.setDatabaseId(databaseAdapter.addFeedIfNotExisting("", url));

			if (callback != null)
			{
				foregroundRequestFeedNetwork(newFeed,callback);
			}
		}
		else
		{
			if (LOGGING)
				Log.e(LOGTAG,"Database not ready: addFeedByURL");
		}
	}

	public void subscribeFeed(Feed feed)
	{
		if (databaseAdapter != null && databaseAdapter.databaseReady())
		{
			feed.setSubscribed(true);
			databaseAdapter.addOrUpdateFeed(feed);
		}
		else
		{
			if (LOGGING)
				Log.e(LOGTAG,"Database not ready: subscribeFeed");
		}
	}

	// Remove this feed from the ones we are listening to. Do we need an
	// "addFeed(Feed...)" as well
	// or do we use the URL-form that's already there, i.e. addFeed(String url)?
	public void unsubscribeFeed(Feed feed)
	{
		if (databaseAdapter != null && databaseAdapter.databaseReady())
		{
			feed.setSubscribed(false);
			databaseAdapter.addOrUpdateFeed(feed);
			// databaseAdapter.deleteFeed(feed.getDatabaseId());
		}
		else
		{
			if (LOGGING)
				Log.e(LOGTAG,"Database not ready: unsubscribeFeed");
		}
	}

	public void removeFeed(Feed feed) {
		if (databaseAdapter != null && databaseAdapter.databaseReady())
		{
			feed.setSubscribed(false);
			databaseAdapter.addOrUpdateFeed(feed);
			databaseAdapter.deleteFeed(feed.getDatabaseId());
		}
		else
		{
			if (LOGGING)
				Log.e(LOGTAG,"Database not ready: removeFeed");
		}
	}

	public String getDebugLog()
	{
		StringBuffer debugLog = new StringBuffer();

		java.util.Date date= new java.util.Date();
		debugLog.append("Timestamp: " + date.getTime() + "\n");

		debugLog.append("OS Version: " + System.getProperty("os.version") + "(" + android.os.Build.VERSION.INCREMENTAL + ")\n");
		debugLog.append("OS API Level: " + android.os.Build.VERSION.SDK_INT + "\n");
		debugLog.append("Device: " + android.os.Build.DEVICE + "\n");
		debugLog.append("Model (and Product): " + android.os.Build.MODEL + " ("+ android.os.Build.PRODUCT + ")\n");
		
		debugLog.append("File Path: " + ioCipherFilePath + "\n");
		
		debugLog.append("Online: ");
		int isOnline = isOnline();
		if (isOnline == ONLINE) {
			debugLog.append("Online\n");
		} else if (isOnline == NOT_ONLINE_NO_TOR) {
			debugLog.append("Not Online, No Proxy\n");
		} else if (isOnline == NOT_ONLINE_NO_WIFI) {
			debugLog.append("Not Online, No Wifi\n");
		} else if (isOnline == NOT_ONLINE_NO_WIFI_OR_NETWORK) {
			debugLog.append("Not Online, No Wifi or Netowrk\n");
		}
		
		debugLog.append("Feed Info\n");
		ArrayList<Feed> subscribedFeeds = getSubscribedFeedsList();
		for (Feed feed : subscribedFeeds) {
			debugLog.append(feed.getDatabaseId() + ", " 
					+ databaseAdapter.getFeedItems(feed.getDatabaseId(), -1).size() + ", "
					+ feed.getStatus() + ", " +  "\n");
		}
		debugLog.append("\n");
		
		debugLog.append("Key:\n"
				+ "STATUS_NOT_SYNCED = 0\n"
				+ "STATUS_LAST_SYNC_GOOD = 1\n"
				+ "STATUS_LAST_SYNC_FAILED_404 = 2\n"
				+ "STATUS_LAST_SYNC_FAILED_UNKNOWN = 3\n"
				+ "STATUS_LAST_SYNC_FAILED_BAD_URL = 4\n"
				+ "STATUS_SYNC_IN_PROGRESS = 5\n"
				+ "STATUS_LAST_SYNC_PARSE_ERROR = 6\n");
		
		
		return debugLog.toString();
	}
	
	public Intent getDebugIntent()
	{		
		Intent sendIntent = new Intent();
		sendIntent.setAction(Intent.ACTION_SEND);
		sendIntent.putExtra(android.content.Intent.EXTRA_SUBJECT, "Debug Log");
		sendIntent.putExtra(android.content.Intent.EXTRA_EMAIL, new String[]{"debug@guardianproject.info"});
		sendIntent.putExtra(Intent.EXTRA_TEXT, getDebugLog());
		sendIntent.setType("text/plain");

		return sendIntent;
	}	
	
	// Stub for Intent.. We don't start an activity here since we are doing a
	// custom chooser in FragmentActivityWithMenu. We could though use a generic
	// chooser
	// Mikael, what do you think?
	// Since the share list is kind of a UI component I guess we shouldn't use a
	// generic chooser.
	// We could perhaps introduce a
	// "doShare(Intent shareInten, ResolveInfo chosenWayToShare)"?
	public Intent getShareIntent(Item item)
	{
		Intent sendIntent = new Intent();
		sendIntent.setAction(Intent.ACTION_SEND);
		sendIntent.putExtra(android.content.Intent.EXTRA_SUBJECT, item.getTitle());
		sendIntent.putExtra(Intent.EXTRA_TEXT, item.getTitle() + "\n" + item.getLink() + "\n" + item.getCleanMainContent());

		sendIntent.putExtra(SocialReader.SHARE_ITEM_ID, item.getDatabaseId());
		sendIntent.setType("text/plain");
		sendIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
		// applicationContext.startActivity(Intent.createChooser(sendIntent,"Share via: "));

		return sendIntent;
	}
	
	public Intent getSecureShareIntent(Item item, boolean onlyPrototype) {
		java.io.File sharingFile = new java.io.File("/test");
		if (!onlyPrototype)
			sharingFile = packageItemNonVFS(item.getDatabaseId());
		
		Intent sendIntent = new Intent();
		sendIntent.setAction(Intent.ACTION_SEND);
		//sendIntent.setDataAndType(Uri.parse(SecureShareContentProvider.CONTENT_URI + "item/" + item.getDatabaseId()),CONTENT_SHARING_MIME_TYPE);
		sendIntent.setDataAndType(Uri.fromFile(sharingFile), CONTENT_SHARING_MIME_TYPE);
		sendIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

		//Log.v(LOGTAG,"Secure Share Intent: " + sendIntent.getDataString());
		
		return sendIntent;
	}

	// Stub for Intent
	public Intent getShareIntent(Feed feed)
	{
	    if (databaseAdapter == null || !databaseAdapter.databaseReady())
	    {
	    	if (LOGGING)
	    		Log.e(LOGTAG,"Database not ready: getShareIntent");
	    	return new Intent();
	    }

		Intent sendIntent = new Intent();
		sendIntent.setAction(Intent.ACTION_SEND);

		if (feed != null)
		{
			sendIntent.putExtra(Intent.EXTRA_TEXT, feed.getTitle() + "\n" + feed.getLink() + "\n" + feed.getFeedURL() + "\n" + feed.getDescription());
		}
		else
		{
			ArrayList<Feed> subscribed = getSubscribedFeedsList();
			StringBuilder builder = new StringBuilder();

			for (Feed subscribedFeed : subscribed)
			{
				if (builder.length() > 0)
					builder.append("\n\n");
				builder.append(subscribedFeed.getTitle() + "\n" + subscribedFeed.getLink() + "\n" + subscribedFeed.getFeedURL() + "\n" + subscribedFeed.getDescription());
			}
			sendIntent.putExtra(Intent.EXTRA_TEXT, builder.toString());
		}
		sendIntent.setType("text/plain");
		sendIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
		return sendIntent;
	}

	public void doWipe(int wipeMethod)
	{
		if (LOGGING)
			Log.v(LOGTAG, "doing doWipe()");

		if (wipeMethod == DATA_WIPE)
		{
			dataWipe();
		}
		else if (wipeMethod == FULL_APP_WIPE)
		{
			dataWipe();
			deleteApp();
		}
		else
		{
			if (LOGGING)
				Log.v(LOGTAG, "This shouldn't happen");
		}

		//applicationContext.finish();
	}

	private void deleteApp()
	{
		Uri packageURI = Uri.parse("package:" + applicationContext.getPackageName());
		Intent uninstallIntent = new Intent(Intent.ACTION_DELETE, packageURI);
		uninstallIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
		applicationContext.startActivity(uninstallIntent);
	}

	private void dataWipe()
	{
		if (LOGGING)
			Log.v(LOGTAG, "deleteDatabase");
		//http://code.google.com/p/android/issues/detail?id=13727

		if (databaseAdapter != null && databaseAdapter.databaseReady()) {
			databaseAdapter.deleteAll();
			databaseAdapter.close();
		}

		if (vfs != null && vfs.isMounted()) {
			vfs.unmount();
			vfs = null;
		}
		
		applicationContext.deleteDatabase(DatabaseHelper.DATABASE_NAME);

		if (LOGGING)
			Log.v(LOGTAG, "Delete data");
		deleteFileSystem();
		
		// Reset Prefs to initial state
		settings.resetSettings();
		
		// Change Password
		/*
		String defaultPassword = "password";
		char[] p = defaultPassword.toCharArray();
		try {
			cacheWord.setPassphrase(p);
		} catch (GeneralSecurityException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
		*/
		
		cacheWord.manuallyLock();
		cacheWord.deinitialize();
		
		
	}

	/*
	public void loadDisplayImageMediaContent(MediaContent mc, ImageView imageView)
	{
		loadDisplayImageMediaContent(mc, imageView, false);
	}

	// This should really be a GUI widget but it is here for now
	// Load the media for a specific item.
	public void loadDisplayImageMediaContent(MediaContent mc, ImageView imageView, boolean forceBitwiseDownload)
	{
		final ImageView finalImageView = imageView;

		MediaDownloaderCallback mdc = new MediaDownloaderCallback()
		{
			@Override
			public void mediaDownloaded(File mediaFile)
			{
					//Log.v(LOGTAG, "mediaDownloaded: " + mediaFile.getAbsolutePath());
				try {

					BufferedInputStream bis = new BufferedInputStream(new FileInputStream(mediaFile));

					BitmapFactory.Options bmpFactoryOptions = new BitmapFactory.Options();
					// Should take into account view size?
					if (finalImageView.getWidth() > 0 && finalImageView.getHeight() > 0)
					{
						//Log.v(LOGTAG, "ImageView dimensions " + finalImageView.getWidth() + " " + finalImageView.getHeight());

						bmpFactoryOptions.inJustDecodeBounds = true;

						//BitmapFactory.decodeFile(mediaFile.getAbsolutePath(), bmpFactoryOptions);
						BitmapFactory.decodeStream(bis, null, bmpFactoryOptions);
						bis.close();

						int heightRatio = (int) Math.ceil(bmpFactoryOptions.outHeight / (float) finalImageView.getHeight());
						int widthRatio = (int) Math.ceil(bmpFactoryOptions.outWidth / (float) finalImageView.getWidth());

						if (heightRatio > 1 && widthRatio > 1)
						{
							if (heightRatio > widthRatio)
							{
								bmpFactoryOptions.inSampleSize = heightRatio;
							}
							else
							{
								bmpFactoryOptions.inSampleSize = widthRatio;
							}
						}

						// Decode it for real
						bmpFactoryOptions.inJustDecodeBounds = false;
					}
					else
					{
						//Log.v(LOGTAG, "ImageView dimensions aren't set");
						bmpFactoryOptions.inSampleSize = 2;
					}

					//Bitmap bmp = BitmapFactory.decodeFile(mediaFile.getAbsolutePath(), bmpFactoryOptions);
					BufferedInputStream bis2 = new BufferedInputStream(new FileInputStream(mediaFile));
					Bitmap bmp = BitmapFactory.decodeStream(bis2, null, bmpFactoryOptions);
					bis2.close();

					finalImageView.setImageBitmap(bmp);
					finalImageView.invalidate();
					//Log.v(LOGTAG, "Should have set bitmap");

				} catch (FileNotFoundException e) {
					e.printStackTrace();
				} catch (IOException e) {
					// TODO Auto-generated catch block
					e.printStackTrace();
				}

			}
		};
		loadImageMediaContent(mc, mdc, forceBitwiseDownload);
	}
	*/

	public boolean isMediaContentLoaded(MediaContent mc)
	{
		return loadMediaContent(mc, null, false, false);
	}
	
	public boolean loadMediaContent(MediaContent mc, MediaDownloaderCallback mdc) {
		return loadMediaContent(mc, mdc, false);
	}

	public boolean loadMediaContent(MediaContent mc, MediaDownloaderCallback mdc, boolean forceBitwiseDownload)
	{
		return loadMediaContent(mc, mdc, true, forceBitwiseDownload);
	}
	
	public boolean loadMediaContent(MediaContent mc, MediaDownloaderCallback mdc, boolean download, boolean forceBitwiseDownload)
	{
		//Log.v(LOGTAG, "loadImageMediaContent: " + mc.getUrl() + " " + mc.getType());
		
		final MediaDownloaderCallback mediaDownloaderCallback = mdc;
		
		if (mc.getMediaContentType() == MediaContentType.EPUB) {
			
			java.io.File possibleFile = new java.io.File(this.getNonVirtualFileSystemDir(), MEDIA_CONTENT_FILE_PREFIX + mc.getDatabaseId());
			
			if (possibleFile.exists())
			{
				//Log.v(LOGTAG, "Already downloaded: " + possibleFile.getAbsolutePath());
				if (mdc != null)
				mdc.mediaDownloadedNonVFS(possibleFile);
				return true;
			}
			else if (download && forceBitwiseDownload && isOnline() == ONLINE)
			//else if ((settings.syncMode() != Settings.SyncMode.BitWise || forceBitwiseDownload) && isOnline() == ONLINE)
			// only want to download this content type if they click it so...
			{
				if (LOGGING)
					Log.v(LOGTAG, "File doesn't exist, downloading");
	
				NonVFSMediaDownloader mediaDownloader = new NonVFSMediaDownloader(this,possibleFile);
				mediaDownloader.setMediaDownloaderCallback(new NonVFSMediaDownloader.MediaDownloaderCallback() {
					@Override
					public void mediaDownloaded(java.io.File mediaFile) {
						mediaFile.setReadable(true, false); // Security alert
						mediaDownloaderCallback.mediaDownloadedNonVFS(mediaFile);
					}
				});
				mediaDownloader.execute(mc);
	
				return true;
			}
			else {
				return false;
			}
			
		} 
		else if (mc.getMediaContentType() == MediaContentType.AUDIO || mc.getMediaContentType() == MediaContentType.VIDEO) 
		{
			File possibleFile = new File(getFileSystemDir(), MEDIA_CONTENT_FILE_PREFIX + mc.getDatabaseId());
			if (possibleFile.exists())
			{
				if (LOGGING)
					Log.v(LOGTAG, "Already downloaded: " + possibleFile.getAbsolutePath());
				if (mdc != null)
				mdc.mediaDownloaded(possibleFile);
				return true;
			}
			else if (download && forceBitwiseDownload && isOnline() == ONLINE)
			{
				if (LOGGING)
					Log.v(LOGTAG, "File doesn't exist, downloading");
	
				MediaDownloader mediaDownloader = new MediaDownloader(this);
				mediaDownloader.setMediaDownloaderCallback(mdc);
	
				mediaDownloader.execute(mc);
	
				return true;
			}
			else
			{
				//if (LOGGING)
				//Log.v(LOGTAG, "Can't download, not online or in bitwise mode");
				return false;
			}			
		}
		else if (mc.getMediaContentType() == MediaContentType.IMAGE) 
		{
			File possibleFile = new File(getFileSystemDir(), MEDIA_CONTENT_FILE_PREFIX + mc.getDatabaseId());
			if (possibleFile.exists())
			{
				if (LOGGING)
					Log.v(LOGTAG, "Already downloaded: " + possibleFile.getAbsolutePath());
				if (mdc != null)
				mdc.mediaDownloaded(possibleFile);
				return true;
			}
			else if (download && (settings.syncMode() != Settings.SyncMode.BitWise || forceBitwiseDownload) && isOnline() == ONLINE)
			{
				if (LOGGING)
					Log.v(LOGTAG, "File doesn't exist, downloading");
	
				MediaDownloader mediaDownloader = new MediaDownloader(this);
				mediaDownloader.setMediaDownloaderCallback(mdc);
	
				mediaDownloader.execute(mc);
	
				return true;
			}
			else
			{
				//if (LOGGING)
				//Log.v(LOGTAG, "Can't download, not online or in bitwise mode");
				return false;
			}
		} else {
			if (LOGGING)
				Log.v(LOGTAG,"Not a media type we support: " + mc.getType());
			return false;
		}
	}
	
	public void deleteMediaContentFile(final int mediaContentDatabaseId) {
		final File possibleFile = new File(getFileSystemDir(), MEDIA_CONTENT_FILE_PREFIX + mediaContentDatabaseId);
		if (possibleFile.exists())
		{
			new Thread() {
				public void run() {
					if (possibleFile.delete()) {

						if (LOGGING)
							Log.v(LOGTAG, "Deleted: " + possibleFile.getAbsolutePath());

						// Update the database
						MediaContent mc = databaseAdapter.getMediaContentById(mediaContentDatabaseId);
						unsetMediaContentDownloaded(mc);

					} else {
						if (LOGGING) 
							Log.v(LOGTAG, "NOT DELETED " + possibleFile.getAbsolutePath());
					}
				}				
			}.start();
		}		
	}

	/*
	public long sizeOfMediaContent() {
		long totalSize = 0;
		String[] possibleMediaFiles = getFileSystemDir().list();
		for (int i = 0; i < possibleMediaFiles.length; i++) {
			if (possibleMediaFiles[i].contains(MEDIA_CONTENT_FILE_PREFIX)) {
				totalSize += new File(possibleMediaFiles[i]).length();
			}
		}
		return totalSize;
	}
	
	public void checkMediaContent() {
		while (sizeOfMediaContent() > mediaCacheSize * 1024 * 1024) {
			
		}
	}
	*/
	
	public File vfsTempItemBundle() {
		File tempContentFile = new File(getVFSSharingDir(), CONTENT_BUNDLE_FILE_PREFIX + System.currentTimeMillis() + Item.DEFAULT_DATABASE_ID + "." + SocialReader.CONTENT_SHARING_EXTENSION);
		return tempContentFile;
	}
	
	public java.io.File nonVfsTempItemBundle() {
		return new java.io.File(getNonVFSSharingDir(), CONTENT_BUNDLE_FILE_PREFIX + Item.DEFAULT_DATABASE_ID + "." + SocialReader.CONTENT_SHARING_EXTENSION);
	}
	
	private java.io.File getNonVFSSharingDir() {
		//java.io.File sharingDir = new java.io.File(getNonVirtualFileSystemInternalDir(), NON_VFS_SHARE_DIRECTORY);
		java.io.File sharingDir = new java.io.File(getNonVirtualFileSystemDir(), NON_VFS_SHARE_DIRECTORY);

		sharingDir.mkdirs();
		return sharingDir;
	}
	
	private File getVFSSharingDir() {
		File sharingDir = new File(getFileSystemDir(), VFS_SHARE_DIRECTORY);
		sharingDir.mkdirs();
		return sharingDir;
	}
	
	public java.io.File packageItemNonVFS(long itemId) {
		
		java.io.File possibleFile = new java.io.File(getNonVFSSharingDir(), CONTENT_BUNDLE_FILE_PREFIX + itemId + "." + SocialReader.CONTENT_SHARING_EXTENSION);
		
		if (LOGGING)
			Log.v(LOGTAG,"Going to package as: " + possibleFile.toString());
		
		if (possibleFile.exists())
		{
			if (LOGGING)
				Log.v(LOGTAG, "item already packaged " + possibleFile.getAbsolutePath());
		}
		else
		{
			if (LOGGING)
				Log.v(LOGTAG, "item not already packaged, going to do so now " + possibleFile.getAbsolutePath());
			
			try {
				
				if (databaseAdapter != null && databaseAdapter.databaseReady())
				{
					byte[] buf = new byte[1024]; 
			        int len; 
					
					Item itemToShare = databaseAdapter.getItemById(itemId);
					
					if (LOGGING)
						Log.v(LOGTAG,"Going to package " + itemToShare.toString());
					
					ZipOutputStream zipOutputStream = new ZipOutputStream(new java.io.FileOutputStream(possibleFile)); 
			        
					// Package content
					File tempItemContentFile = new File(this.getFileSystemDir(), SocialReader.TEMP_ITEM_CONTENT_FILE_NAME);
			        ObjectOutputStream output = new ObjectOutputStream(new BufferedOutputStream(new FileOutputStream(tempItemContentFile)));
			        output.writeObject(itemToShare);
			        output.flush();
			        output.close();
			        
			        zipOutputStream.putNextEntry(new ZipEntry(tempItemContentFile.getName()));
			        FileInputStream in = new FileInputStream(tempItemContentFile);
			        
			        while ((len = in.read(buf)) > 0) { 
			        	zipOutputStream.write(buf, 0, len); 
			        } 
			        zipOutputStream.closeEntry(); 
			        in.close(); 
			        // Finished content

			        // Now do media
					ArrayList<MediaContent> mc = itemToShare.getMediaContent();
					for (MediaContent mediaContent : mc) {
						File mediaFile = new File(getFileSystemDir(), MEDIA_CONTENT_FILE_PREFIX + mediaContent.getDatabaseId());
						if (LOGGING)
							Log.v(LOGTAG,"Checking for " + mediaFile.getAbsolutePath());
						
						if (mediaFile.exists())
						{
							if (LOGGING)
								Log.v(LOGTAG, "Media exists, adding it: " + mediaFile.getAbsolutePath());
							zipOutputStream.putNextEntry(new ZipEntry(mediaFile.getName()));
							FileInputStream mIn = new FileInputStream(mediaFile);
					        while ((len = mIn.read(buf)) > 0) { 
					        	zipOutputStream.write(buf, 0, len); 
					        } 
					        zipOutputStream.closeEntry(); 
					        mIn.close(); 
						} else {
							if (LOGGING)
								Log.v(LOGTAG, "Media doesn't exist, not adding it");
						}
					}
					
					zipOutputStream.close();
				}
				else
				{
					if (LOGGING)
						Log.e(LOGTAG,"Database not ready: packageItem");
				}
			} catch (FileNotFoundException fnfe) {
				if (LOGGING)
					Log.e(LOGTAG,"Can't write package file, not found");
			} catch (IOException e) {
				// TODO Auto-generated catch block
				if (LOGGING)
					e.printStackTrace();

			}

		}
		possibleFile.setReadable(true, false);
		return possibleFile;		
	}

	public Item getItemFromId(long itemId)
	{
		if (databaseAdapter != null && databaseAdapter.databaseReady())
		{
			Item item = databaseAdapter.getItemById(itemId);
			return item;
		}
		else
		{
			if (LOGGING)
				Log.e(LOGTAG, "Database not ready: getItemFromId");
		}
		return null;
	}

	public File packageItem(long itemId)
	{
		// IOCipher File
		File possibleFile = new File(getVFSSharingDir(), CONTENT_BUNDLE_FILE_PREFIX + itemId + "." + SocialReader.CONTENT_SHARING_EXTENSION);
		if (LOGGING)
			Log.v(LOGTAG,"possibleFile: " + possibleFile.getAbsolutePath());
		
		if (possibleFile.exists())
		{
			if (LOGGING)
				Log.v(LOGTAG, "item already packaged " + possibleFile.getAbsolutePath());
		}
		else
		{
			Log.v(LOGTAG, "item not already packaged, going to do so now " + possibleFile.getAbsolutePath());
			
			try {

				if (databaseAdapter != null && databaseAdapter.databaseReady())
				{
					byte[] buf = new byte[1024]; 
			        int len; 
					
					Item itemToShare = databaseAdapter.getItemById(itemId);
					
					if (LOGGING)
						Log.v(LOGTAG,"Going to package " + itemToShare.toString());
					
					ZipOutputStream zipOutputStream = new ZipOutputStream(new FileOutputStream(possibleFile)); 
			        
					// Package content
					File tempItemContentFile = new File(this.getFileSystemDir(), SocialReader.TEMP_ITEM_CONTENT_FILE_NAME);
			        ObjectOutputStream output = new ObjectOutputStream(new BufferedOutputStream(new FileOutputStream(tempItemContentFile)));
			        output.writeObject(itemToShare);
			        output.flush();
			        output.close();
			        
			        zipOutputStream.putNextEntry(new ZipEntry(tempItemContentFile.getName()));
			        FileInputStream in = new FileInputStream(tempItemContentFile);
			        
			        while ((len = in.read(buf)) > 0) { 
			        	zipOutputStream.write(buf, 0, len); 
			        } 
			        zipOutputStream.closeEntry(); 
			        in.close(); 
			        // Finished content

			        // Now do media
					ArrayList<MediaContent> mc = itemToShare.getMediaContent();
					for (MediaContent mediaContent : mc) {
						File mediaFile = new File(getFileSystemDir(), MEDIA_CONTENT_FILE_PREFIX + mediaContent.getDatabaseId());
						if (LOGGING)
							Log.v(LOGTAG,"Checking for " + mediaFile.getAbsolutePath());
						if (mediaFile.exists())
						{
							if (LOGGING)
								Log.v(LOGTAG, "Media exists, adding it: " + mediaFile.getAbsolutePath());
							zipOutputStream.putNextEntry(new ZipEntry(mediaFile.getName()));
							FileInputStream mIn = new FileInputStream(mediaFile);
					        while ((len = mIn.read(buf)) > 0) { 
					        	zipOutputStream.write(buf, 0, len); 
					        } 
					        zipOutputStream.closeEntry(); 
					        mIn.close(); 
						} else {
							if (LOGGING)
								Log.v(LOGTAG, "Media doesn't exist, not adding it");
						}
					}
					
					zipOutputStream.close();
				}
				else
				{
					if (LOGGING)
						Log.e(LOGTAG,"Database not ready: packageItem");
				}
			} catch (FileNotFoundException fnfe) {
				fnfe.printStackTrace();
				if (LOGGING)
					Log.e(LOGTAG,"Can't write package file, not found");
			} catch (IOException e) {
				e.printStackTrace();
			}

		}
		return possibleFile;
	}

    @Override
    public void onCacheWordUninitialized() {
    	if (LOGGING)
    		Log.v(LOGTAG,"onCacheWordUninitialized");

    	uninitialize();
    }

    @Override
    public void onCacheWordLocked() {
    	if (LOGGING)
    		Log.v(LOGTAG, "onCacheWordLocked");

    	uninitialize();
    }

    @Override
    public void onCacheWordOpened() {
    	if (LOGGING)
    		Log.v(LOGTAG,"onCacheWordOpened");
        initialize();
    }
    
	public void getStoreBitmapDimensions(MediaContent mediaContent)
	{
		try
		{
			File mediaFile = new File(getFileSystemDir(), SocialReader.MEDIA_CONTENT_FILE_PREFIX + mediaContent.getDatabaseId());

			BitmapFactory.Options o = new BitmapFactory.Options();
			o.inJustDecodeBounds = true;
			BufferedInputStream bis = new BufferedInputStream(new FileInputStream(mediaFile));
			BitmapFactory.decodeStream(bis, null, o);
			bis.close();
			if (o.outWidth > 0 && o.outHeight > 0)
			{
				mediaContent.setWidth(o.outWidth);
				mediaContent.setHeight(o.outHeight);
				databaseAdapter.updateItemMedia(mediaContent);
			}
		}
		catch (FileNotFoundException e)
		{
			e.printStackTrace();
		}
		catch (IOException e)
		{
			e.printStackTrace();
		}
	}
}
