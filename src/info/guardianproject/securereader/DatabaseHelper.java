package info.guardianproject.securereader;

import info.guardianproject.cacheword.CacheWordHandler;
import info.guardianproject.cacheword.SQLCipherOpenHelper;
import net.sqlcipher.database.SQLiteDatabase;
import android.content.Context;
import android.util.Log;

public class DatabaseHelper extends SQLCipherOpenHelper
{
	public static String LOGTAG = "DatabaseHelper";
	public static boolean LOGGING = false;

	public static final String DATABASE_NAME = "bigbuffalo.db";
	public static final int DATABASE_VERSION = 2;

	public static final int POSTS_FEED_ID = -99;
	public static final int DRAFTS_FEED_ID = -98;

	public static final String FEEDS_TABLE = "feeds";
	public static final String FEEDS_TABLE_COLUMN_ID = "feed_id";
	public static final String FEEDS_TABLE_TITLE = "feed_title";
	public static final String FEEDS_TABLE_DESCRIPTION = "feed_description";
	public static final String FEEDS_TABLE_LAST_BUILD_DATE = "feed_build_date";
	public static final String FEEDS_TABLE_LINK = "feed_link";
	public static final String FEEDS_TABLE_FEED_URL = "feed_url";
	public static final String FEEDS_TABLE_PUBLISH_DATE = "feed_publish_date";
	public static final String FEEDS_TABLE_LANGUAGE = "feed_language";
	public static final String FEEDS_TABLE_NETWORK_PULL_DATE = "feed_network_pull_date";
	public static final String FEEDS_TABLE_SUBSCRIBED = "feed_subscribed";
	public static final String FEEDS_TABLE_STATUS = "feed_status";
	
	public static final String FEEDS_TABLE_CREATE_SQL = "create table " + FEEDS_TABLE + " (" + FEEDS_TABLE_COLUMN_ID + " integer primary key autoincrement, "
			+ FEEDS_TABLE_NETWORK_PULL_DATE + " text null," + FEEDS_TABLE_TITLE + " text not null," + FEEDS_TABLE_FEED_URL + " text not null,"
			+ FEEDS_TABLE_LINK + " text null, " + FEEDS_TABLE_DESCRIPTION + " text null," + FEEDS_TABLE_PUBLISH_DATE + " text null,"
			+ FEEDS_TABLE_LAST_BUILD_DATE + " text null," + FEEDS_TABLE_LANGUAGE + " text null," + FEEDS_TABLE_SUBSCRIBED + " integer default 0, " + FEEDS_TABLE_STATUS + " integer default 0);";

	public static final String ITEMS_TABLE = "items";
	public static final String ITEMS_TABLE_COLUMN_ID = "item_id";
	public static final String ITEMS_TABLE_FEED_ID = "item_feed_id";
	public static final String ITEMS_TABLE_AUTHOR = "item_author";
	public static final String ITEMS_TABLE_CATEGORY = "item_category";
	public static final String ITEMS_TABLE_COMMENTS_URL = "item_comments_url";
	public static final String ITEMS_TABLE_DESCRIPTION = "item_description";
	public static final String ITEMS_TABLE_CONTENT_ENCODED = "item_content_encoded";
	public static final String ITEMS_TABLE_GUID = "item_guid";
	public static final String ITEMS_TABLE_LINK = "item_link";
	public static final String ITEMS_TABLE_PUBLISH_DATE = "item_publish_date";
	public static final String ITEMS_TABLE_SOURCE = "item_source";
	public static final String ITEMS_TABLE_TITLE = "item_title";
	public static final String ITEMS_TABLE_FAVORITE = "item_favorite"; // boolean
																		// default
																		// 0
	public static final String ITEMS_TABLE_SHARED = "item_shared"; // boolean default 0
	public static final String ITEMS_TABLE_VIEWCOUNT = "item_viewcount"; 

	public static final String ITEMS_TABLE_CREATE_SQL = "create table " + ITEMS_TABLE + " (" + ITEMS_TABLE_COLUMN_ID + " integer primary key autoincrement, "
			+ ITEMS_TABLE_FEED_ID + " integer not null, " + ITEMS_TABLE_TITLE + " text null, " + ITEMS_TABLE_LINK + " text null, " + ITEMS_TABLE_DESCRIPTION
			+ " text null, " + ITEMS_TABLE_CONTENT_ENCODED + " text null, " + ITEMS_TABLE_PUBLISH_DATE + " text null, " + ITEMS_TABLE_GUID + " text null, "
			+ ITEMS_TABLE_AUTHOR + " text null, " + ITEMS_TABLE_COMMENTS_URL + " text null, " + ITEMS_TABLE_SOURCE + " text null, " + ITEMS_TABLE_CATEGORY
			+ " text null, " + ITEMS_TABLE_FAVORITE + " boolean default 0, " + ITEMS_TABLE_SHARED + " boolean default 0, " + ITEMS_TABLE_VIEWCOUNT + " integer default 0);";
	
	public static final String ITEMS_TABLE_CREATE_INDEX = "create index item_publish_date_index on " + ITEMS_TABLE + " (" + ITEMS_TABLE_PUBLISH_DATE + ");";

	public static final String ITEM_MEDIA_TABLE = "item_media";
	public static final String ITEM_MEDIA_TABLE_COLUMN_ID = "item_media_id";
	public static final String ITEM_MEDIA_ITEM_ID = "item_media_item_id";
	public static final String ITEM_MEDIA_URL = "item_media_url";
	public static final String ITEM_MEDIA_TYPE = "item_media_type";
	public static final String ITEM_MEDIA_MEDIUM = "item_media_medium";
	public static final String ITEM_MEDIA_HEIGHT = "item_media_height";
	public static final String ITEM_MEDIA_WIDTH = "item_media_width";
	public static final String ITEM_MEDIA_FILESIZE = "item_media_filesize";
	public static final String ITEM_MEDIA_DURATION = "item_media_duration";
	public static final String ITEM_MEDIA_DEFAULT = "item_media_default";
	public static final String ITEM_MEDIA_EXPRESSION = "item_media_expression";
	public static final String ITEM_MEDIA_BITRATE = "item_media_bitrate";
	public static final String ITEM_MEDIA_FRAMERATE = "item_media_framerate";
	public static final String ITEM_MEDIA_LANG = "item_media_lang";
	public static final String ITEM_MEDIA_SAMPLE_RATE = "item_media_sample_rate";
	
	public static final String ITEM_MEDIA_DOWNLOADED = "item_media_downloaded";
	
	public static final String ITEMS_MEDIA_TABLE_CREATE_SQL = "create table " + ITEM_MEDIA_TABLE + " (" 
			+ ITEM_MEDIA_TABLE_COLUMN_ID + " integer primary key autoincrement, " 
			+ ITEM_MEDIA_ITEM_ID + " integer not null, " 
			+ ITEM_MEDIA_URL + " text not null, " 
			+ ITEM_MEDIA_TYPE + " text null, " 
			+ ITEM_MEDIA_MEDIUM + " text null, " 
			+ ITEM_MEDIA_HEIGHT + " integer null, " 
			+ ITEM_MEDIA_WIDTH + " integer null, "
			+ ITEM_MEDIA_FILESIZE + " long null, " 
			+ ITEM_MEDIA_DURATION + " text null, " 
			+ ITEM_MEDIA_DEFAULT + " boolean default 0, " 
			+ ITEM_MEDIA_DOWNLOADED + " boolean default 0, "
			+ ITEM_MEDIA_EXPRESSION + " text null, " 
			+ ITEM_MEDIA_BITRATE + " integer null, " 
			+ ITEM_MEDIA_FRAMERATE + " integer null, " 
			+ ITEM_MEDIA_LANG + " text null, " 
			+ ITEM_MEDIA_SAMPLE_RATE + " text null);";

	public static final String SETTINGS_TABLE = "settings";
	public static final String SETTINGS_TABLE_ID = "settings_id";
	public static final String SETTINGS_TABLE_KEY = "settings_key";
	public static final String SETTINGS_TABLE_VALUE = "settings_value";
	
	public static final String SETTINGS_TABLE_CREATE_SQL = "create table " + SETTINGS_TABLE + " (" + SETTINGS_TABLE_ID + " integer primary key autoincrement, "
			+ SETTINGS_TABLE_KEY + " text not null, " + SETTINGS_TABLE_VALUE + " text not null);";
	
	/*
	public static final String TAGS_TABLE = "tags";
	public static final String TAGS_TABLE_ID = "tags_id";
	public static final String TAGS_TABLE_TAG = "tag";
	
	public static final String TAGS_TABLE_CREATE_SQL =  "create table " + TAGS_TABLE + " (" + TAGS_TABLE_ID + " integer primary key autoincrement, "
			+ TAGS_TABLE_TAG + " text not null);";
	*/
	
	public static final String ITEM_TAGS_TABLE = "item_tags";
	public static final String ITEM_TAGS_TABLE_ID = "item_tags_id";
	//public static final String ITEM_TAGS_TAG_ID = TAGS_TABLE_ID;
	public static final String ITEM_TAG = "tag";
	public static final String ITEM_TAGS_TABLE_ITEM_ID = ITEMS_TABLE_COLUMN_ID;
	
	public static final String ITEM_TAGS_TABLE_CREATE_SQL =  "create table " + ITEM_TAGS_TABLE + " (" + ITEM_TAGS_TABLE_ID + " integer primary key autoincrement, "
			+ ITEM_TAG + " text not null, " + ITEM_TAGS_TABLE_ITEM_ID + " integer not null);";
	
	
	private SQLiteDatabase sqliteDatabase;

	DatabaseHelper(CacheWordHandler cacheWord, Context context)
	{
		super(cacheWord, context, DATABASE_NAME, null, DATABASE_VERSION);
	}

	@Override
	public void onCreate(SQLiteDatabase _sqliteDatabase)
	{
		if (LOGGING)
			Log.v(LOGTAG, "SQL: " + ITEMS_TABLE_CREATE_SQL);
		_sqliteDatabase.execSQL(ITEMS_TABLE_CREATE_SQL);
		
		if (LOGGING)
			Log.v(LOGTAG, "SQL: " + ITEMS_TABLE_CREATE_INDEX);
		_sqliteDatabase.execSQL(ITEMS_TABLE_CREATE_INDEX);

		if (LOGGING)
			Log.v(LOGTAG, "SQL: " + FEEDS_TABLE_CREATE_SQL);
		_sqliteDatabase.execSQL(FEEDS_TABLE_CREATE_SQL);

		if (LOGGING)
			Log.v(LOGTAG, "SQL: " + ITEMS_MEDIA_TABLE_CREATE_SQL);
		_sqliteDatabase.execSQL(ITEMS_MEDIA_TABLE_CREATE_SQL);
		
		if (LOGGING)
			Log.v(LOGTAG, "SQL: " + SETTINGS_TABLE_CREATE_SQL);
		_sqliteDatabase.execSQL(SETTINGS_TABLE_CREATE_SQL);
		
		/*
		 * if (LOGGING)
			Log.v(LOGTAG, "SQL: " + TAGS_TABLE_CREATE_SQL);
		_sqliteDatabase.execSQL(TAGS_TABLE_CREATE_SQL);
		*/
		if (LOGGING)
			Log.v(LOGTAG, "SQL: " + ITEM_TAGS_TABLE_CREATE_SQL);
		_sqliteDatabase.execSQL(ITEM_TAGS_TABLE_CREATE_SQL);		
	}

	@Override
	public void onUpgrade(SQLiteDatabase _sqliteDatabase, int oldVersion, int newVersion)
	{
		if (newVersion >= oldVersion && newVersion >= 2) {
			// Moving from 1 to 2

			String ITEMS_TABLE_ALTER_SQL = "alter table " + ITEMS_TABLE + " add column " + ITEMS_TABLE_VIEWCOUNT +  " integer default 0";
			_sqliteDatabase.execSQL(ITEMS_TABLE_ALTER_SQL);

			String RENAME_ITEMS_MEDIA_TABLE = "alter table " + ITEM_MEDIA_TABLE + " rename to " + ITEM_MEDIA_TABLE + "_old";
			_sqliteDatabase.execSQL(RENAME_ITEMS_MEDIA_TABLE);
			
			_sqliteDatabase.execSQL(ITEMS_MEDIA_TABLE_CREATE_SQL);
			
			String populateTable = "insert into " + ITEM_MEDIA_TABLE + "(" 
										+ ITEM_MEDIA_TABLE_COLUMN_ID + ", " 
										+ ITEM_MEDIA_URL + ", " 
										+ ITEM_MEDIA_TYPE + ", " 
										+ ITEM_MEDIA_MEDIUM + ", " 
										+ ITEM_MEDIA_HEIGHT + ", " 
										+ ITEM_MEDIA_WIDTH + ", "
										+ ITEM_MEDIA_FILESIZE + ", " 
										+ ITEM_MEDIA_DURATION + ", " 
										+ ITEM_MEDIA_DEFAULT + ", " 
										+ ITEM_MEDIA_EXPRESSION + ", " 
										+ ITEM_MEDIA_BITRATE + ", " 
										+ ITEM_MEDIA_FRAMERATE + ", " 
										+ ITEM_MEDIA_LANG + ", "
										+ ITEM_MEDIA_SAMPLE_RATE + ") " +  
									"select " 
										+ ITEM_MEDIA_TABLE_COLUMN_ID + ", " 
										+ ITEM_MEDIA_URL + ", " 
										+ ITEM_MEDIA_TYPE + ", " 
										+ ITEM_MEDIA_MEDIUM + ", " 
										+ ITEM_MEDIA_HEIGHT + ", " 
										+ ITEM_MEDIA_WIDTH + ", "
										+ ITEM_MEDIA_FILESIZE + ", " 
										+ ITEM_MEDIA_DURATION + ", " 
										+ ITEM_MEDIA_DEFAULT + ", " 
										+ ITEM_MEDIA_EXPRESSION + ", " 
										+ ITEM_MEDIA_BITRATE + ", " 
										+ ITEM_MEDIA_FRAMERATE + ", " 
										+ ITEM_MEDIA_LANG + ", "
										+ ITEM_MEDIA_SAMPLE_RATE + " from " 
									+ ITEM_MEDIA_TABLE + "_old" + " where " 
										+ ITEM_MEDIA_URL + " != null and " 
										+ ITEM_MEDIA_ITEM_ID + " != null";
			
			
			_sqliteDatabase.execSQL(populateTable);
			
			String dropTable = "drop table " + ITEM_MEDIA_TABLE + "_old";
			_sqliteDatabase.execSQL(dropTable);
		}
	}
}
