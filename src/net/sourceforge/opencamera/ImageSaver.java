package net.sourceforge.opencamera;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.DateFormat;
import java.text.DecimalFormat;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;

import net.sourceforge.opencamera.CameraController.CameraController;
import android.annotation.SuppressLint;
import android.annotation.TargetApi;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Paint.Align;
import android.hardware.camera2.DngCreator;
import android.location.Location;
import android.media.ExifInterface;
import android.media.Image;
import android.net.Uri;
import android.os.Build;
import android.util.Log;

/** Handles the saving (and any required processing) of photos.
 */
public class ImageSaver extends Thread {
	private static final String TAG = "ImageSaver";

	private static final String TAG_GPS_IMG_DIRECTION = "GPSImgDirection";
	private static final String TAG_GPS_IMG_DIRECTION_REF = "GPSImgDirectionRef";

	private Paint p = new Paint();
	private DecimalFormat decimalFormat = new DecimalFormat("#0.0");
	
	private MainActivity main_activity = null;

	/* We use a separate count n_images_to_save, rather than just relying on the queue size, so we can take() an image from queue,
	 * but only decrement the count when we've finished saving the image.
	 * In general, n_images_to_save represents the number of images still to process, including ones currently being processed.
	 * Therefore we should always have n_images_to_save >= queue.size().
	 */
	private int n_images_to_save = 0;
	private BlockingQueue<Request> queue = new ArrayBlockingQueue<Request>(1); // since we remove from the queue and then process in the saver thread, in practice the number of background photos - including the one being processed - is one more than the length of this queue
	
	private static class Request {
		boolean is_raw = false;
		byte [] data = null; // for jpeg
		DngCreator dngCreator = null; // for raw
		Image image = null; // for raw
		boolean image_capture_intent = false;
		Uri image_capture_intent_uri = null;
		boolean using_camera2 = false;
		int image_quality = 0;
		boolean do_auto_stabilise = false;
		double level_angle = 0.0;
		boolean is_front_facing = false;
		Date current_date = null;
		String preference_stamp = null;
		String preference_textstamp = null;
		int font_size = 0;
		int color = 0;
		String pref_style = null;
		String preference_stamp_dateformat = null;
		String preference_stamp_timeformat = null;
		String preference_stamp_gpsformat = null;
		boolean store_location = false;
		Location location = null;
		boolean store_geo_direction = false;
		double geo_direction = 0.0;
		boolean has_thumbnail_animation = false;
		
		Request(boolean is_raw,
			byte [] data,
			DngCreator dngCreator, Image image,
			boolean image_capture_intent, Uri image_capture_intent_uri,
			boolean using_camera2, int image_quality,
			boolean do_auto_stabilise, double level_angle,
			boolean is_front_facing,
			Date current_date,
			String preference_stamp, String preference_textstamp, int font_size, int color, String pref_style, String preference_stamp_dateformat, String preference_stamp_timeformat, String preference_stamp_gpsformat,
			boolean store_location, Location location, boolean store_geo_direction, double geo_direction,
			boolean has_thumbnail_animation) {
			this.is_raw = is_raw;
			this.data = data;
			this.dngCreator = dngCreator;
			this.image = image;
			this.image_capture_intent = image_capture_intent;
			this.image_capture_intent_uri = image_capture_intent_uri;
			this.using_camera2 = using_camera2;
			this.image_quality = image_quality;
			this.do_auto_stabilise = do_auto_stabilise;
			this.level_angle = level_angle;
			this.is_front_facing = is_front_facing;
			this.current_date = current_date;
			this.preference_stamp = preference_stamp;
			this.preference_textstamp = preference_textstamp;
			this.font_size = font_size;
			this.color = color;
			this.pref_style = pref_style;
			this.preference_stamp_dateformat = preference_stamp_dateformat;
			this.preference_stamp_timeformat = preference_stamp_timeformat;
			this.preference_stamp_gpsformat = preference_stamp_gpsformat;
			this.store_location = store_location;
			this.location = location;
			this.store_geo_direction = store_geo_direction;
			this.geo_direction = geo_direction;
			this.has_thumbnail_animation = has_thumbnail_animation;
		}
	}

	ImageSaver(MainActivity main_activity) {
		if( MyDebug.LOG )
			Log.d(TAG, "ImageSaver");
		this.main_activity = main_activity;

		p.setAntiAlias(true);
	}
	
	@Override
	public void run() {
		if( MyDebug.LOG )
			Log.d(TAG, "starting ImageSaver thread...");
		while( true ) {
			try {
				if( MyDebug.LOG )
					Log.d(TAG, "ImageSaver thread reading from queue, size: " + queue.size());
				Request request = queue.take(); // if empty, take() blocks until non-empty
				// Only decrement n_images_to_save after we've actually saved the image! Otherwise waitUntilDone() will return
				// even though we still have a last image to be saved.
				if( MyDebug.LOG )
					Log.d(TAG, "ImageSaver thread found new request from queue, size is now: " + queue.size());
				boolean success = false;
				if( request.is_raw ) {
					success = saveImageNowRaw(request.dngCreator, request.image, request.current_date);
				}
				else {
					success = saveImageNow(request);
				}
				if( MyDebug.LOG ) {
					if( success )
						Log.d(TAG, "ImageSaver thread successfully saved image");
					else
						Log.e(TAG, "ImageSaver thread failed to save image");
				}
				synchronized( this ) {
					n_images_to_save--;
					if( MyDebug.LOG )
						Log.d(TAG, "ImageSaver thread processed new request from queue, images to save is now: " + n_images_to_save);
					if( MyDebug.LOG && n_images_to_save < 0 ) {
						Log.e(TAG, "images to save has become negative");
						throw new RuntimeException();
					}
					notifyAll();
				}
			}
			catch(InterruptedException e) {
				e.printStackTrace();
				if( MyDebug.LOG )
					Log.e(TAG, "interrupted while trying to read from ImageSaver queue");
			}
		}
	}
	
	/** Saves a photo.
	 *  If do_in_background is true, the photo will be saved in a background thread. If the queue is full, the function will wait
	 *  until it isn't full. Otherwise it will return immediately. The function always returns true for background saving.
	 *  If do_in_background is false, the photo is saved on the current thread, and the function returns whether the photo was saved
	 *  successfully.
	 */
	public boolean saveImageJpeg(boolean do_in_background,
			byte [] data,
			boolean image_capture_intent, Uri image_capture_intent_uri,
			boolean using_camera2, int image_quality,
			boolean do_auto_stabilise, double level_angle,
			boolean is_front_facing,
			Date current_date,
			String preference_stamp, String preference_textstamp, int font_size, int color, String pref_style, String preference_stamp_dateformat, String preference_stamp_timeformat, String preference_stamp_gpsformat,
			boolean store_location, Location location, boolean store_geo_direction, double geo_direction,
			boolean has_thumbnail_animation) {
		if( MyDebug.LOG ) {
			Log.d(TAG, "saveImageJpeg");
			Log.d(TAG, "do_in_background? " + do_in_background);
		}
		return saveImage(do_in_background,
				false,
				data,
				null, null,
				image_capture_intent, image_capture_intent_uri,
				using_camera2, image_quality,
				do_auto_stabilise, level_angle,
				is_front_facing,
				current_date,
				preference_stamp, preference_textstamp, font_size, color, pref_style, preference_stamp_dateformat, preference_stamp_timeformat, preference_stamp_gpsformat,
				store_location, location, store_geo_direction, geo_direction,
				has_thumbnail_animation);
	}

	/** Saves a RAW photo.
	 *  If do_in_background is true, the photo will be saved in a background thread. If the queue is full, the function will wait
	 *  until it isn't full. Otherwise it will return immediately. The function always returns true for background saving.
	 *  If do_in_background is false, the photo is saved on the current thread, and the function returns whether the photo was saved
	 *  successfully.
	 */
	public boolean saveImageRaw(boolean do_in_background,
			DngCreator dngCreator, Image image,
			Date current_date) {
		if( MyDebug.LOG ) {
			Log.d(TAG, "saveImageRaw");
			Log.d(TAG, "do_in_background? " + do_in_background);
		}
		return saveImage(do_in_background,
				true,
				null,
				dngCreator, image,
				false, null,
				false, 0,
				false, 0.0,
				false,
				current_date,
				null, null, 0, 0, null, null, null, null,
				false, null, false, 0.0,
				false);
	}

	/** Internal saveImage method to handle both JPEG and RAW.
	 */
	private boolean saveImage(boolean do_in_background,
			boolean is_raw,
			byte [] data,
			DngCreator dngCreator, Image image,
			boolean image_capture_intent, Uri image_capture_intent_uri,
			boolean using_camera2, int image_quality,
			boolean do_auto_stabilise, double level_angle,
			boolean is_front_facing,
			Date current_date,
			String preference_stamp, String preference_textstamp, int font_size, int color, String pref_style, String preference_stamp_dateformat, String preference_stamp_timeformat, String preference_stamp_gpsformat,
			boolean store_location, Location location, boolean store_geo_direction, double geo_direction,
			boolean has_thumbnail_animation) {
		if( MyDebug.LOG ) {
			Log.d(TAG, "saveImage");
			Log.d(TAG, "do_in_background? " + do_in_background);
		}
		boolean success = false;
		
		//do_in_background = false;
		
		Request request = new Request(is_raw,
				data,
				dngCreator, image,
				image_capture_intent, image_capture_intent_uri,
				using_camera2, image_quality,
				do_auto_stabilise, level_angle,
				is_front_facing,
				current_date,
				preference_stamp, preference_textstamp, font_size, color, pref_style, preference_stamp_dateformat, preference_stamp_timeformat, preference_stamp_gpsformat,
				store_location, location, store_geo_direction, geo_direction,
				has_thumbnail_animation);

		if( do_in_background ) {
			// this should not be synchronized on "this": BlockingQueue is thread safe, and if it's blocking in queue.put(), we'll hang because
			// the saver queue will need to synchronize on "this" in order to notifyAll() the main thread
			boolean done = false;
			while( !done ) {
				try {
					if( MyDebug.LOG )
						Log.d(TAG, "ImageSaver thread adding to queue, size: " + queue.size());
					synchronized( this ) {
						// see above for why we don't synchronize the queue.put call
						// but we synchronize modification to avoid risk of problems related to compiler optimisation (local caching or reordering)
						// also see FindBugs warning due to inconsistent synchronisation
						n_images_to_save++; // increment before adding to the queue, just to make sure the main thread doesn't think we're all done
					}
					queue.put(request); // if queue is full, put() blocks until it isn't full
					if( MyDebug.LOG ) {
						synchronized( this ) { // keep FindBugs happy
							Log.d(TAG, "ImageSaver thread added to queue, size is now: " + queue.size());
							Log.d(TAG, "images still to save is now: " + n_images_to_save);
						}
					}
					done = true;
				}
				catch(InterruptedException e) {
					e.printStackTrace();
					if( MyDebug.LOG )
						Log.e(TAG, "interrupted while trying to add to ImageSaver queue");
				}
			}
			success = true; // always return true when done in background
		}
		else {
			// wait for queue to be empty
			waitUntilDone();
			if( is_raw ) {
				success = saveImageNowRaw(dngCreator, image, current_date);
			}
			else {
				success = saveImageNow(request);
			}
		}

		if( MyDebug.LOG )
			Log.d(TAG, "success: " + success);
		return success;
	}

	/** Wait until the queue is empty and all pending images have been saved.
	 */
	void waitUntilDone() {
		if( MyDebug.LOG )
			Log.d(TAG, "waitUntilDone");
		synchronized( this ) {
			if( MyDebug.LOG ) {
				Log.d(TAG, "queue is size " + queue.size());
				Log.d(TAG, "images still to save " + n_images_to_save);
			}
			while( n_images_to_save > 0 ) {
				if( MyDebug.LOG )
					Log.d(TAG, "wait until done...");
				try {
					wait();
				}
				catch(InterruptedException e) {
					e.printStackTrace();
					if( MyDebug.LOG )
						Log.e(TAG, "interrupted while waiting for ImageSaver queue to be empty");
				}
				if( MyDebug.LOG ) {
					Log.d(TAG, "waitUntilDone: queue is size " + queue.size());
					Log.d(TAG, "waitUntilDone: images still to save " + n_images_to_save);
				}
			}
		}
		if( MyDebug.LOG )
			Log.d(TAG, "waitUntilDone: images all saved");
	}

	/** May be run in saver thread or picture callback thread (depending on whether running in backgrond).
	 */
	@SuppressLint("SimpleDateFormat")
	@SuppressWarnings("deprecation")
	private boolean saveImageNow(final Request request) {
		if( MyDebug.LOG )
			Log.d(TAG, "saveImageNow");

		if( request.is_raw ) {
			if( MyDebug.LOG )
				Log.d(TAG, "saveImageNow called with raw request");
			// throw runtime exception, as this is a programming error
			throw new RuntimeException();
		}
		// unpack:
		byte [] data = request.data;
		boolean image_capture_intent = request.image_capture_intent;
		boolean using_camera2 = request.using_camera2;
		Date current_date = request.current_date;
		boolean store_location = request.store_location;
		boolean store_geo_direction = request.store_geo_direction;
		
        boolean success = false;
		final MyApplicationInterface applicationInterface = main_activity.getApplicationInterface();
		StorageUtils storageUtils = main_activity.getStorageUtils();
		
		main_activity.savingImage(true);

		Bitmap bitmap = null;
		if( request.do_auto_stabilise )
		{
			double level_angle = request.level_angle;
			while( level_angle < -90 )
				level_angle += 180;
			while( level_angle > 90 )
				level_angle -= 180;
			if( MyDebug.LOG )
				Log.d(TAG, "auto stabilising... angle: " + level_angle);
			BitmapFactory.Options options = new BitmapFactory.Options();
			//options.inMutable = true;
			if( Build.VERSION.SDK_INT <= Build.VERSION_CODES.KITKAT ) {
				// setting is ignored in Android 5 onwards
				options.inPurgeable = true;
			}
			bitmap = BitmapFactory.decodeByteArray(data, 0, data.length, options);
			if( bitmap == null ) {
				main_activity.getPreview().showToast(null, R.string.failed_to_auto_stabilise);
	            System.gc();
			}
			else {
    			int width = bitmap.getWidth();
    			int height = bitmap.getHeight();
    			if( MyDebug.LOG ) {
    				Log.d(TAG, "level_angle: " + level_angle);
    				Log.d(TAG, "decoded bitmap size " + width + ", " + height);
    				Log.d(TAG, "bitmap size: " + width*height*4);
    			}
    			/*for(int y=0;y<height;y++) {
    				for(int x=0;x<width;x++) {
    					int col = bitmap.getPixel(x, y);
    					col = col & 0xffff0000; // mask out red component
    					bitmap.setPixel(x, y, col);
    				}
    			}*/
    		    Matrix matrix = new Matrix();
    		    double level_angle_rad_abs = Math.abs( Math.toRadians(level_angle) );
    		    int w1 = width, h1 = height;
    		    double w0 = (w1 * Math.cos(level_angle_rad_abs) + h1 * Math.sin(level_angle_rad_abs));
    		    double h0 = (w1 * Math.sin(level_angle_rad_abs) + h1 * Math.cos(level_angle_rad_abs));
    		    // apply a scale so that the overall image size isn't increased
    		    float orig_size = w1*h1;
    		    float rotated_size = (float)(w0*h0);
    		    float scale = (float)Math.sqrt(orig_size/rotated_size);
    			if( main_activity.test_low_memory ) {
        			if( MyDebug.LOG )
        				Log.d(TAG, "TESTING LOW MEMORY");
    		    	scale *= 2.0f; // test 20MP on Galaxy Nexus or Nexus 7; 52MP on Nexus 6
    			}
    			if( MyDebug.LOG ) {
    				Log.d(TAG, "w0 = " + w0 + " , h0 = " + h0);
    				Log.d(TAG, "w1 = " + w1 + " , h1 = " + h1);
    				Log.d(TAG, "scale = sqrt " + orig_size + " / " + rotated_size + " = " + scale);
    			}
    		    matrix.postScale(scale, scale);
    		    w0 *= scale;
    		    h0 *= scale;
    		    w1 *= scale;
    		    h1 *= scale;
    			if( MyDebug.LOG ) {
    				Log.d(TAG, "after scaling: w0 = " + w0 + " , h0 = " + h0);
    				Log.d(TAG, "after scaling: w1 = " + w1 + " , h1 = " + h1);
    			}
    		    if( request.is_front_facing ) {
        		    matrix.postRotate((float)-level_angle);
    		    }
    		    else {
        		    matrix.postRotate((float)level_angle);
    		    }
    		    Bitmap new_bitmap = Bitmap.createBitmap(bitmap, 0, 0, width, height, matrix, true);
    		    // careful, as new_bitmap is sometimes not a copy!
    		    if( new_bitmap != bitmap ) {
    		    	bitmap.recycle();
    		    	bitmap = new_bitmap;
    		    }
	            System.gc();
    			if( MyDebug.LOG ) {
    				Log.d(TAG, "rotated and scaled bitmap size " + bitmap.getWidth() + ", " + bitmap.getHeight());
    				Log.d(TAG, "rotated and scaled bitmap size: " + bitmap.getWidth()*bitmap.getHeight()*4);
    			}
    			double tan_theta = Math.tan(level_angle_rad_abs);
    			double sin_theta = Math.sin(level_angle_rad_abs);
    			double denom = (double)( h0/w0 + tan_theta );
    			double alt_denom = (double)( w0/h0 + tan_theta );
    			if( denom == 0.0 || denom < 1.0e-14 ) {
    	    		if( MyDebug.LOG )
    	    			Log.d(TAG, "zero denominator?!");
    			}
    			else if( alt_denom == 0.0 || alt_denom < 1.0e-14 ) {
    	    		if( MyDebug.LOG )
    	    			Log.d(TAG, "zero alt denominator?!");
    			}
    			else {
        			int w2 = (int)(( h0 + 2.0*h1*sin_theta*tan_theta - w0*tan_theta ) / denom);
        			int h2 = (int)(w2*h0/(double)w0);
        			int alt_h2 = (int)(( w0 + 2.0*w1*sin_theta*tan_theta - h0*tan_theta ) / alt_denom);
        			int alt_w2 = (int)(alt_h2*w0/(double)h0);
        			if( MyDebug.LOG ) {
        				//Log.d(TAG, "h0 " + h0 + " 2.0*h1*sin_theta*tan_theta " + 2.0*h1*sin_theta*tan_theta + " w0*tan_theta " + w0*tan_theta + " / h0/w0 " + h0/w0 + " tan_theta " + tan_theta);
        				Log.d(TAG, "w2 = " + w2 + " , h2 = " + h2);
        				Log.d(TAG, "alt_w2 = " + alt_w2 + " , alt_h2 = " + alt_h2);
        			}
        			if( alt_w2 < w2 ) {
            			if( MyDebug.LOG ) {
            				Log.d(TAG, "chose alt!");
            			}
        				w2 = alt_w2;
        				h2 = alt_h2;
        			}
        			if( w2 <= 0 )
        				w2 = 1;
        			else if( w2 >= bitmap.getWidth() )
        				w2 = bitmap.getWidth()-1;
        			if( h2 <= 0 )
        				h2 = 1;
        			else if( h2 >= bitmap.getHeight() )
        				h2 = bitmap.getHeight()-1;
        			int x0 = (bitmap.getWidth()-w2)/2;
        			int y0 = (bitmap.getHeight()-h2)/2;
        			if( MyDebug.LOG ) {
        				Log.d(TAG, "x0 = " + x0 + " , y0 = " + y0);
        			}
        			new_bitmap = Bitmap.createBitmap(bitmap, x0, y0, w2, h2);
        		    if( new_bitmap != bitmap ) {
        		    	bitmap.recycle();
        		    	bitmap = new_bitmap;
        		    }
    	            System.gc();
    			}
			}
		}
		boolean dategeo_stamp = request.preference_stamp.equals("preference_stamp_yes");
		boolean text_stamp = request.preference_textstamp.length() > 0;
		if( dategeo_stamp || text_stamp ) {
			if( bitmap == null ) {
    			if( MyDebug.LOG )
    				Log.d(TAG, "decode bitmap in order to stamp info");
				BitmapFactory.Options options = new BitmapFactory.Options();
				options.inMutable = true;
				if( Build.VERSION.SDK_INT <= Build.VERSION_CODES.KITKAT ) {
					// setting is ignored in Android 5 onwards
					options.inPurgeable = true;
				}
    			bitmap = BitmapFactory.decodeByteArray(data, 0, data.length, options);
    			if( bitmap == null ) {
    				main_activity.getPreview().showToast(null, R.string.failed_to_stamp);
    	            System.gc();
    			}
			}
			if( bitmap != null ) {
    			if( MyDebug.LOG )
    				Log.d(TAG, "stamp info to bitmap");
    			int font_size = request.font_size;
    			int color = request.color;
    			String pref_style = request.pref_style;
    			String preference_stamp_dateformat = request.preference_stamp_dateformat;
    			String preference_stamp_timeformat = request.preference_stamp_timeformat;
    			String preference_stamp_gpsformat = request.preference_stamp_gpsformat;
    			int width = bitmap.getWidth();
    			int height = bitmap.getHeight();
    			if( MyDebug.LOG ) {
    				Log.d(TAG, "decoded bitmap size " + width + ", " + height);
    				Log.d(TAG, "bitmap size: " + width*height*4);
    			}
    			Canvas canvas = new Canvas(bitmap);
    			p.setColor(Color.WHITE);
    			// we don't use the density of the screen, because we're stamping to the image, not drawing on the screen (we don't want the font height to depend on the device's resolution)
    			// instead we go by 1 pt == 1/72 inch height, and scale for an image height (or width if in portrait) of 4" (this means the font height is also independent of the photo resolution)
    			int smallest_size = (width<height) ? width : height;
    			float scale = ((float)smallest_size) / (72.0f*4.0f);
    			int font_size_pixel = (int)(font_size * scale + 0.5f); // convert pt to pixels
    			if( MyDebug.LOG ) {
    				Log.d(TAG, "scale: " + scale);
    				Log.d(TAG, "font_size: " + font_size);
    				Log.d(TAG, "font_size_pixel: " + font_size_pixel);
    			}
    			p.setTextSize(font_size_pixel);
    	        int offset_x = (int)(8 * scale + 0.5f); // convert pt to pixels
    	        int offset_y = (int)(8 * scale + 0.5f); // convert pt to pixels
    	        int diff_y = (int)((font_size+4) * scale + 0.5f); // convert pt to pixels
    	        int ypos = height - offset_y;
    	        p.setTextAlign(Align.RIGHT);
    	        boolean draw_shadowed = false;
    			if( pref_style.equals("preference_stamp_style_shadowed") ) {
    				draw_shadowed = true;
    			}
    			else if( pref_style.equals("preference_stamp_style_plain") ) {
    				draw_shadowed = false;
    			}
    	        if( dategeo_stamp ) {
        			if( MyDebug.LOG )
        				Log.d(TAG, "stamp date");
        			// doesn't respect user preferences such as 12/24 hour - see note about in draw() about DateFormat.getTimeInstance()
        			String date_stamp = "", time_stamp = "";
        			if( !preference_stamp_dateformat.equals("preference_stamp_dateformat_none") ) {
            			if( preference_stamp_dateformat.equals("preference_stamp_dateformat_yyyymmdd") )
	        				date_stamp = new SimpleDateFormat("yyyy/MM/dd").format(current_date);
            			else if( preference_stamp_dateformat.equals("preference_stamp_dateformat_ddmmyyyy") )
	        				date_stamp = new SimpleDateFormat("dd/MM/yyyy").format(current_date);
            			else if( preference_stamp_dateformat.equals("preference_stamp_dateformat_mmddyyyy") )
	        				date_stamp = new SimpleDateFormat("MM/dd/yyyy").format(current_date);
	        			else // default
	        				date_stamp = DateFormat.getDateInstance().format(current_date);
        			}
        			if( !preference_stamp_timeformat.equals("preference_stamp_timeformat_none") ) {
            			if( preference_stamp_timeformat.equals("preference_stamp_timeformat_12hour") )
	        				time_stamp = new SimpleDateFormat("hh:mm:ss a").format(current_date);
            			else if( preference_stamp_timeformat.equals("preference_stamp_timeformat_24hour") )
            				time_stamp = new SimpleDateFormat("HH:mm:ss").format(current_date);
	        			else // default
	            	        time_stamp = DateFormat.getTimeInstance().format(current_date);
        			}
        			if( MyDebug.LOG ) {
        				Log.d(TAG, "date_stamp: " + date_stamp);
        				Log.d(TAG, "time_stamp: " + time_stamp);
        			}
        			if( date_stamp.length() > 0 || time_stamp.length() > 0 ) {
        				String datetime_stamp = "";
        				if( date_stamp.length() > 0 )
        					datetime_stamp += date_stamp;
        				if( time_stamp.length() > 0 ) {
            				if( date_stamp.length() > 0 )
            					datetime_stamp += " ";
        					datetime_stamp += time_stamp;
        				}
    					applicationInterface.drawTextWithBackground(canvas, p, datetime_stamp, color, Color.BLACK, width - offset_x, ypos, false, null, draw_shadowed);
        			}
    				ypos -= diff_y;
    				String gps_stamp = "";
        			if( !preference_stamp_gpsformat.equals("preference_stamp_gpsformat_none") ) {
	    				if( store_location ) {
	    					Location location = request.location;
	            			if( preference_stamp_gpsformat.equals("preference_stamp_gpsformat_dms") )
	            				gps_stamp += LocationSupplier.locationToDMS(location.getLatitude()) + ", " + LocationSupplier.locationToDMS(location.getLongitude());
            				else
            					gps_stamp += Location.convert(location.getLatitude(), Location.FORMAT_DEGREES) + ", " + Location.convert(location.getLongitude(), Location.FORMAT_DEGREES);
	    					if( location.hasAltitude() ) {
	    						gps_stamp += ", " + decimalFormat.format(location.getAltitude()) + main_activity.getResources().getString(R.string.metres_abbreviation);
	    					}
	    				}
				    	if( store_geo_direction ) {
				    		double geo_direction = request.geo_direction;
							float geo_angle = (float)Math.toDegrees(geo_direction);
							if( geo_angle < 0.0f ) {
								geo_angle += 360.0f;
							}
		        			if( MyDebug.LOG )
		        				Log.d(TAG, "geo_angle: " + geo_angle);
	    			    	if( gps_stamp.length() > 0 )
	    			    		gps_stamp += ", ";
	    			    	gps_stamp += "" + Math.round(geo_angle) + (char)0x00B0;
				    	}
        			}
			    	if( gps_stamp.length() > 0 ) {
	        			if( MyDebug.LOG )
	        				Log.d(TAG, "stamp with location_string: " + gps_stamp);
	        			applicationInterface.drawTextWithBackground(canvas, p, gps_stamp, color, Color.BLACK, width - offset_x, ypos, false, null, draw_shadowed);
	    				ypos -= diff_y;
			    	}
    	        }
    	        if( text_stamp ) {
        			if( MyDebug.LOG )
        				Log.d(TAG, "stamp text");
        			applicationInterface.drawTextWithBackground(canvas, p, request.preference_textstamp, color, Color.BLACK, width - offset_x, ypos, false, null, draw_shadowed);
    				ypos -= diff_y;
    	        }
			}
		}

		int exif_orientation_s = ExifInterface.ORIENTATION_UNDEFINED;
		File picFile = null;
		Uri saveUri = null; // if non-null, then picFile is a temporary file, which afterwards we should redirect to saveUri
        try {
			if( image_capture_intent ) {
    			if( MyDebug.LOG )
    				Log.d(TAG, "image_capture_intent");
    			if( request.image_capture_intent_uri != null )
    			{
    			    // Save the bitmap to the specified URI (use a try/catch block)
        			if( MyDebug.LOG )
        				Log.d(TAG, "save to: " + request.image_capture_intent_uri);
        			saveUri = request.image_capture_intent_uri;
    			}
    			else
    			{
    			    // If the intent doesn't contain an URI, send the bitmap as a parcel
    			    // (it is a good idea to reduce its size to ~50k pixels before)
        			if( MyDebug.LOG )
        				Log.d(TAG, "sent to intent via parcel");
    				if( bitmap == null ) {
	        			if( MyDebug.LOG )
	        				Log.d(TAG, "create bitmap");
	    				BitmapFactory.Options options = new BitmapFactory.Options();
	    				//options.inMutable = true;
	    				if( Build.VERSION.SDK_INT <= Build.VERSION_CODES.KITKAT ) {
	    					// setting is ignored in Android 5 onwards
	    					options.inPurgeable = true;
	    				}
	        			bitmap = BitmapFactory.decodeByteArray(data, 0, data.length, options);
    				}
    				if( bitmap != null ) {
	        			int width = bitmap.getWidth();
	        			int height = bitmap.getHeight();
	        			if( MyDebug.LOG ) {
	        				Log.d(TAG, "decoded bitmap size " + width + ", " + height);
	        				Log.d(TAG, "bitmap size: " + width*height*4);
	        			}
	        			final int small_size_c = 128;
	        			if( width > small_size_c ) {
	        				float scale = ((float)small_size_c)/(float)width;
		        			if( MyDebug.LOG )
		        				Log.d(TAG, "scale to " + scale);
		        		    Matrix matrix = new Matrix();
		        		    matrix.postScale(scale, scale);
		        		    Bitmap new_bitmap = Bitmap.createBitmap(bitmap, 0, 0, width, height, matrix, true);
		        		    // careful, as new_bitmap is sometimes not a copy!
		        		    if( new_bitmap != bitmap ) {
		        		    	bitmap.recycle();
		        		    	bitmap = new_bitmap;
		        		    }
		        		}
    				}
        			if( MyDebug.LOG ) {
        				if( bitmap != null ) {
	        				Log.d(TAG, "returned bitmap size " + bitmap.getWidth() + ", " + bitmap.getHeight());
	        				Log.d(TAG, "returned bitmap size: " + bitmap.getWidth()*bitmap.getHeight()*4);
        				}
        				else {
	        				Log.e(TAG, "no bitmap created");
        				}
        			}
        			if( bitmap != null )
        				main_activity.setResult(Activity.RESULT_OK, new Intent("inline-data").putExtra("data", bitmap));
        			main_activity.finish();
    			}
			}
			else if( storageUtils.isUsingSAF() ) {
				saveUri = storageUtils.createOutputMediaFileSAF(StorageUtils.MEDIA_TYPE_IMAGE, "jpg", current_date);
			}
			else {
    			picFile = storageUtils.createOutputMediaFile(StorageUtils.MEDIA_TYPE_IMAGE, "jpg", current_date);
	    		if( MyDebug.LOG )
	    			Log.d(TAG, "save to: " + picFile.getAbsolutePath());
			}
			
			if( saveUri != null && picFile == null ) {
	    		if( MyDebug.LOG )
	    			Log.d(TAG, "saveUri: " + saveUri);
				picFile = File.createTempFile("picFile", "jpg", main_activity.getCacheDir());
	    		if( MyDebug.LOG )
	    			Log.d(TAG, "temp picFile: " + picFile.getAbsolutePath());
			}
			
			OutputStream outputStream = null;
			if( picFile != null ) {
	            outputStream = new FileOutputStream(picFile);
			}

			if( outputStream != null ) {
				try {
		            if( bitmap != null ) {
	    	            bitmap.compress(Bitmap.CompressFormat.JPEG, request.image_quality, outputStream);
		            }
		            else {
		            	outputStream.write(data);
		            }
				}
				finally {
					outputStream.close();
				}
	    		if( MyDebug.LOG )
	    			Log.d(TAG, "saveImageNow saved photo");

	    		if( saveUri == null ) { // if saveUri is non-null, then we haven't succeeded until we've copied to the saveUri
	    			success = true;
	    		}
	            if( picFile != null ) {
	            	if( bitmap != null ) {
	            		// need to update EXIF data!
        	    		if( MyDebug.LOG )
        	    			Log.d(TAG, "write temp file to record EXIF data");
	            		File tempFile = File.createTempFile("opencamera_exif", "");
	    	            OutputStream tempOutputStream = new FileOutputStream(tempFile);
	    	            try {
	    	            	tempOutputStream.write(data);
	    	            }
	    	            finally {
	    	            	tempOutputStream.close();
	    	            }
        	    		if( MyDebug.LOG )
        	    			Log.d(TAG, "read back EXIF data");
    	            	ExifInterface exif = new ExifInterface(tempFile.getAbsolutePath());
    	            	String exif_aperture = exif.getAttribute(ExifInterface.TAG_APERTURE);
    	            	String exif_datetime = exif.getAttribute(ExifInterface.TAG_DATETIME);
    	            	String exif_exposure_time = exif.getAttribute(ExifInterface.TAG_EXPOSURE_TIME);
    	            	String exif_flash = exif.getAttribute(ExifInterface.TAG_FLASH);
    	            	String exif_focal_length = exif.getAttribute(ExifInterface.TAG_FOCAL_LENGTH);
    	            	String exif_gps_altitude = exif.getAttribute(ExifInterface.TAG_GPS_ALTITUDE);
    	            	String exif_gps_altitude_ref = exif.getAttribute(ExifInterface.TAG_GPS_ALTITUDE_REF);
    	            	String exif_gps_datestamp = exif.getAttribute(ExifInterface.TAG_GPS_DATESTAMP);
    	            	String exif_gps_latitude = exif.getAttribute(ExifInterface.TAG_GPS_LATITUDE);
    	            	String exif_gps_latitude_ref = exif.getAttribute(ExifInterface.TAG_GPS_LATITUDE_REF);
    	            	String exif_gps_longitude = exif.getAttribute(ExifInterface.TAG_GPS_LONGITUDE);
    	            	String exif_gps_longitude_ref = exif.getAttribute(ExifInterface.TAG_GPS_LONGITUDE_REF);
    	            	String exif_gps_processing_method = exif.getAttribute(ExifInterface.TAG_GPS_PROCESSING_METHOD);
    	            	String exif_gps_timestamp = exif.getAttribute(ExifInterface.TAG_GPS_TIMESTAMP);
    	            	// leave width/height, as this will have changed!
    	            	String exif_iso = exif.getAttribute(ExifInterface.TAG_ISO);
    	            	String exif_make = exif.getAttribute(ExifInterface.TAG_MAKE);
    	            	String exif_model = exif.getAttribute(ExifInterface.TAG_MODEL);
    	            	int exif_orientation = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_UNDEFINED);
    	            	exif_orientation_s = exif_orientation; // store for later use (for the thumbnail, to save rereading it)
    	            	String exif_white_balance = exif.getAttribute(ExifInterface.TAG_WHITE_BALANCE);

    					if( !tempFile.delete() ) {
    						if( MyDebug.LOG )
    							Log.e(TAG, "failed to delete temp " + tempFile.getAbsolutePath());
    					}
    	            	if( MyDebug.LOG )
        	    			Log.d(TAG, "now write new EXIF data");
    	            	ExifInterface exif_new = new ExifInterface(picFile.getAbsolutePath());
    	            	if( exif_aperture != null )
    	            		exif_new.setAttribute(ExifInterface.TAG_APERTURE, exif_aperture);
    	            	if( exif_datetime != null )
    	            		exif_new.setAttribute(ExifInterface.TAG_DATETIME, exif_datetime);
    	            	if( exif_exposure_time != null )
    	            		exif_new.setAttribute(ExifInterface.TAG_EXPOSURE_TIME, exif_exposure_time);
    	            	if( exif_flash != null )
        	            	exif_new.setAttribute(ExifInterface.TAG_FLASH, exif_flash);
        	            if( exif_focal_length != null )
        	            	exif_new.setAttribute(ExifInterface.TAG_FOCAL_LENGTH, exif_focal_length);
        	            if( exif_gps_altitude != null )
        	            	exif_new.setAttribute(ExifInterface.TAG_GPS_ALTITUDE, exif_gps_altitude);
        	            if( exif_gps_altitude_ref != null )
        	            	exif_new.setAttribute(ExifInterface.TAG_GPS_ALTITUDE_REF, exif_gps_altitude_ref);
        	            if( exif_gps_datestamp != null )
        	            	exif_new.setAttribute(ExifInterface.TAG_GPS_DATESTAMP, exif_gps_datestamp);
        	            if( exif_gps_latitude != null )
        	            	exif_new.setAttribute(ExifInterface.TAG_GPS_LATITUDE, exif_gps_latitude);
        	            if( exif_gps_latitude_ref != null )
        	            	exif_new.setAttribute(ExifInterface.TAG_GPS_LATITUDE_REF, exif_gps_latitude_ref);
        	            if( exif_gps_longitude != null )
        	            	exif_new.setAttribute(ExifInterface.TAG_GPS_LONGITUDE, exif_gps_longitude);
        	            if( exif_gps_longitude_ref != null )
        	            	exif_new.setAttribute(ExifInterface.TAG_GPS_LONGITUDE_REF, exif_gps_longitude_ref);
        	            if( exif_gps_processing_method != null )
        	            	exif_new.setAttribute(ExifInterface.TAG_GPS_PROCESSING_METHOD, exif_gps_processing_method);
        	            if( exif_gps_timestamp != null )
        	            	exif_new.setAttribute(ExifInterface.TAG_GPS_TIMESTAMP, exif_gps_timestamp);
    	            	// leave width/height, as this will have changed!
        	            if( exif_iso != null )
        	            	exif_new.setAttribute(ExifInterface.TAG_ISO, exif_iso);
        	            if( exif_make != null )
        	            	exif_new.setAttribute(ExifInterface.TAG_MAKE, exif_make);
        	            if( exif_model != null )
        	            	exif_new.setAttribute(ExifInterface.TAG_MODEL, exif_model);
        	            if( exif_orientation != ExifInterface.ORIENTATION_UNDEFINED )
        	            	exif_new.setAttribute(ExifInterface.TAG_ORIENTATION, "" + exif_orientation);
        	            if( exif_white_balance != null )
        	            	exif_new.setAttribute(ExifInterface.TAG_WHITE_BALANCE, exif_white_balance);
        	            setGPSDirectionExif(exif_new, store_geo_direction, request.geo_direction);
        	            setDateTimeExif(exif_new);
        	            if( needGPSTimestampHack(using_camera2, store_location) ) {
        	            	fixGPSTimestamp(exif_new);
        	            }
    	            	exif_new.saveAttributes();
        	    		if( MyDebug.LOG )
        	    			Log.d(TAG, "now saved EXIF data");
	            	}
	            	else if( store_geo_direction ) {
    	            	if( MyDebug.LOG )
        	    			Log.d(TAG, "add GPS direction exif info");
    	            	long time_s = System.currentTimeMillis();
    	            	ExifInterface exif = new ExifInterface(picFile.getAbsolutePath());
        	            setGPSDirectionExif(exif, store_geo_direction, request.geo_direction);
        	            setDateTimeExif(exif);
        	            if( needGPSTimestampHack(using_camera2, store_location) ) {
        	            	fixGPSTimestamp(exif);
        	            }
    	            	exif.saveAttributes();
        	    		if( MyDebug.LOG ) {
        	    			Log.d(TAG, "done adding GPS direction exif info, time taken: " + (System.currentTimeMillis() - time_s));
        	    		}
	            	}
	            	else if( needGPSTimestampHack(using_camera2, store_location) ) {
    	            	if( MyDebug.LOG )
        	    			Log.d(TAG, "remove GPS timestamp hack");
    	            	long time_s = System.currentTimeMillis();
    	            	ExifInterface exif = new ExifInterface(picFile.getAbsolutePath());
    	            	fixGPSTimestamp(exif);
    	            	exif.saveAttributes();
        	    		if( MyDebug.LOG ) {
        	    			Log.d(TAG, "done removing GPS timestamp exif info, time taken: " + (System.currentTimeMillis() - time_s));
        	    		}
	            	}

    	            if( saveUri == null ) {
    	            	// broadcast for SAF is done later, when we've actually written out the file
    	            	storageUtils.broadcastFile(picFile, true, false, true);
    	            	main_activity.test_last_saved_image = picFile.getAbsolutePath();
    	            }
	            }
	            if( image_capture_intent ) {
    	    		if( MyDebug.LOG )
    	    			Log.d(TAG, "finish activity due to being called from intent");
	            	main_activity.setResult(Activity.RESULT_OK);
	            	main_activity.finish();
	            }
	            if( storageUtils.isUsingSAF() ) {
	            	// most Gallery apps don't seem to recognise the SAF-format Uri, so just clear the field
	            	storageUtils.clearLastMediaScanned();
	            }

	            if( saveUri != null ) {
	            	copyFileToUri(main_activity, saveUri, picFile);
	    		    success = true;
	    		    /* We still need to broadcastFile for SAF for two reasons:
	    		    	1. To call storageUtils.announceUri() to broadcast NEW_PICTURE etc.
	    		           Whilst in theory we could do this directly, it seems external apps that use such broadcasts typically
	    		           won't know what to do with a SAF based Uri (e.g, Owncloud crashes!) so better to broadcast the Uri
	    		           corresponding to the real file, if it exists.
	    		        2. Whilst the new file seems to be known by external apps such as Gallery without having to call media
	    		           scanner, I've had reports this doesn't happen when saving to external SD cards. So better to explicitly
	    		           scan.
	    		    */
		    	    File real_file = storageUtils.getFileFromDocumentUriSAF(saveUri);
					if( MyDebug.LOG )
						Log.d(TAG, "real_file: " + real_file);
                    if( real_file != null ) {
    					if( MyDebug.LOG )
    						Log.d(TAG, "broadcast file");
    	            	storageUtils.broadcastFile(real_file, true, false, true);
    	            	main_activity.test_last_saved_image = real_file.getAbsolutePath();
                    }
                    else if( !image_capture_intent ) {
    					if( MyDebug.LOG )
    						Log.d(TAG, "announce SAF uri");
                    	// announce the SAF Uri
                    	// (shouldn't do this for a capture intent - e.g., causes crash when calling from Google Keep)
    	    		    storageUtils.announceUri(saveUri, true, false);
                    }
	            }
	        }
		}
        catch(FileNotFoundException e) {
    		if( MyDebug.LOG )
    			Log.e(TAG, "File not found: " + e.getMessage());
            e.printStackTrace();
            main_activity.getPreview().showToast(null, R.string.failed_to_save_photo);
        }
        catch(IOException e) {
    		if( MyDebug.LOG )
    			Log.e(TAG, "I/O error writing file: " + e.getMessage());
            e.printStackTrace();
            main_activity.getPreview().showToast(null, R.string.failed_to_save_photo);
        }

        if( success && saveUri == null ) {
        	applicationInterface.setLastImage(picFile);
        }
        else if( success && storageUtils.isUsingSAF() ){
        	applicationInterface.setLastImageSAF(saveUri);
        }
        else {
        	applicationInterface.clearLastImage();
        }

		// I have received crashes where camera_controller was null - could perhaps happen if this thread was running just as the camera is closing?
        if( success && main_activity.getPreview().getCameraController() != null ) {
        	// update thumbnail - this should be done after restarting preview, so that the preview is started asap
        	long time_s = System.currentTimeMillis();
        	CameraController.Size size = main_activity.getPreview().getCameraController().getPictureSize();
    		int ratio = (int) Math.ceil((double) size.width / main_activity.getPreview().getView().getWidth());
    		int sample_size = Integer.highestOneBit(ratio) * 4; // * 4 to increase performance, without noticeable loss in visual quality
			if( !request.has_thumbnail_animation ) {
				// can use lower resolution if we don't have the thumbnail animation
				sample_size *= 4;
			}
    		if( MyDebug.LOG ) {
    			Log.d(TAG, "    picture width: " + size.width);
    			Log.d(TAG, "    preview width: " + main_activity.getPreview().getView().getWidth());
    			Log.d(TAG, "    ratio        : " + ratio);
    			Log.d(TAG, "    sample_size  : " + sample_size);
    		}
    		Bitmap thumbnail = null;
			if( bitmap == null ) {
				BitmapFactory.Options options = new BitmapFactory.Options();
				options.inMutable = false;
				if( Build.VERSION.SDK_INT <= Build.VERSION_CODES.KITKAT ) {
					// setting is ignored in Android 5 onwards
					options.inPurgeable = true;
				}
				options.inSampleSize = sample_size;
    			thumbnail = BitmapFactory.decodeByteArray(data, 0, data.length, options);
			}
			else {
    			int width = bitmap.getWidth();
    			int height = bitmap.getHeight();
    		    Matrix matrix = new Matrix();
    		    float scale = 1.0f / (float)sample_size;
    		    matrix.postScale(scale, scale);
	    		if( MyDebug.LOG )
	    			Log.d(TAG, "    scale: " + scale);
    		    thumbnail = Bitmap.createBitmap(bitmap, 0, 0, width, height, matrix, true);
			}
			if( thumbnail == null ) {
				// received crashes on Google Play suggesting that thumbnail could not be created
	    		if( MyDebug.LOG )
	    			Log.e(TAG, "failed to create thumbnail bitmap");
			}
			else {
				// now get the rotation from the Exif data
				thumbnail = rotateForExif(thumbnail, exif_orientation_s, picFile.getAbsolutePath());

	    		final Bitmap thumbnail_f = thumbnail;
		    	main_activity.runOnUiThread(new Runnable() {
					public void run() {
						applicationInterface.updateThumbnail(thumbnail_f);
					}
				});
	    		if( MyDebug.LOG )
	    			Log.d(TAG, "    time to create thumbnail: " + (System.currentTimeMillis() - time_s));
			}
        }

        if( bitmap != null ) {
		    bitmap.recycle();
		    bitmap = null;
        }

        if( picFile != null && saveUri != null ) {
    		if( MyDebug.LOG )
    			Log.d(TAG, "delete temp picFile: " + picFile);
        	if( !picFile.delete() ) {
        		if( MyDebug.LOG )
        			Log.e(TAG, "failed to delete temp picFile: " + picFile);
        	}
        	picFile = null;
        }
        
        System.gc();
        
		main_activity.savingImage(false);

        return success;
	}

	/** May be run in saver thread or picture callback thread (depending on whether running in backgrond).
	 */
	@TargetApi(Build.VERSION_CODES.LOLLIPOP)
	private boolean saveImageNowRaw(DngCreator dngCreator, Image image, Date current_date) {
		if( MyDebug.LOG )
			Log.d(TAG, "saveImageNowRaw");

        boolean success = false;
		if( Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP ) {
			if( MyDebug.LOG )
				Log.e(TAG, "RAW requires LOLLIPOP or higher");
			return success;
		}
		StorageUtils storageUtils = main_activity.getStorageUtils();
		
		main_activity.savingImage(true);

        OutputStream output = null;
        try {
    		File picFile = null;
    		Uri saveUri = null;

			if( storageUtils.isUsingSAF() ) {
				saveUri = storageUtils.createOutputMediaFileSAF(StorageUtils.MEDIA_TYPE_IMAGE, "dng", current_date);
	    		if( MyDebug.LOG )
	    			Log.d(TAG, "saveUri: " + saveUri);
	    		// When using SAF, we don't save to a temp file first (unlike for JPEGs). Firstly we don't need to modify Exif, so don't
	    		// need a real file; secondly copying to a temp file is much slower for RAW.
			}
			else {
        		picFile = storageUtils.createOutputMediaFile(StorageUtils.MEDIA_TYPE_IMAGE, "dng", current_date);
	    		if( MyDebug.LOG )
	    			Log.d(TAG, "save to: " + picFile.getAbsolutePath());
			}

    		if( picFile != null ) {
    			output = new FileOutputStream(picFile);
    		}
    		else {
    		    output = main_activity.getContentResolver().openOutputStream(saveUri);
    		}
            dngCreator.writeImage(output, image);
    		image.close();
    		image = null;
    		dngCreator.close();
    		dngCreator = null;
    		output.close();
    		output = null;

    		Location location = null;
    		if( main_activity.getApplicationInterface().getGeotaggingPref() ) {
    			location = main_activity.getApplicationInterface().getLocation();
	    		if( MyDebug.LOG )
	    			Log.d(TAG, "location: " + location);
    		}

    		if( saveUri == null ) {
    			success = true;
        		//Uri media_uri = storageUtils.broadcastFileRaw(picFile, current_date, location);
    		    //storageUtils.announceUri(media_uri, true, false);    			
            	storageUtils.broadcastFile(picFile, true, false, false);
    		}
    		else {
    		    success = true;
	    	    File real_file = storageUtils.getFileFromDocumentUriSAF(saveUri);
				if( MyDebug.LOG )
					Log.d(TAG, "real_file: " + real_file);
                if( real_file != null ) {
					if( MyDebug.LOG )
						Log.d(TAG, "broadcast file");
	        		//Uri media_uri = storageUtils.broadcastFileRaw(real_file, current_date, location);
	    		    //storageUtils.announceUri(media_uri, true, false);
	    		    storageUtils.broadcastFile(real_file, true, false, false);
                }
                else {
					if( MyDebug.LOG )
						Log.d(TAG, "announce SAF uri");
	    		    storageUtils.announceUri(saveUri, true, false);
                }
            }

    		MyApplicationInterface applicationInterface = main_activity.getApplicationInterface();
    		if( success && saveUri == null ) {
            	applicationInterface.setLastImageRaw(picFile);
            }
            else if( success && storageUtils.isUsingSAF() ){
            	applicationInterface.setLastImageRawSAF(saveUri);
            }
            else {
            	applicationInterface.clearLastImageRaw();
            }

        }
        catch(FileNotFoundException e) {
    		if( MyDebug.LOG )
    			Log.e(TAG, "File not found: " + e.getMessage());
            e.printStackTrace();
            main_activity.getPreview().showToast(null, R.string.failed_to_save_photo_raw);
        }
        catch(IOException e) {
			if( MyDebug.LOG )
				Log.e(TAG, "ioexception writing raw image file");
            e.printStackTrace();
            main_activity.getPreview().showToast(null, R.string.failed_to_save_photo_raw);
        }
        finally {
        	if( output != null ) {
				try {
					output.close();
				}
				catch(IOException e) {
					if( MyDebug.LOG )
						Log.e(TAG, "ioexception closing raw output");
					e.printStackTrace();
				}
        	}
        	if( image != null ) {
        		image.close();
        		image = null;
        	}
        	if( dngCreator != null ) {
        		dngCreator.close();
        		dngCreator = null;
        	}
        }


    	System.gc();

        main_activity.savingImage(false);

        return success;
	}

    private Bitmap rotateForExif(Bitmap bitmap, int exif_orientation_s, String path) {
		try {
			if( exif_orientation_s == ExifInterface.ORIENTATION_UNDEFINED ) {
				// haven't already read the exif orientation (or it didn't exist?)
	    		if( MyDebug.LOG )
	    			Log.d(TAG, "    read exif orientation");
            	ExifInterface exif = new ExifInterface(path);
            	exif_orientation_s = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_UNDEFINED);
			}
    		if( MyDebug.LOG )
    			Log.d(TAG, "    exif orientation string: " + exif_orientation_s);
    		boolean needs_tf = false;
			int exif_orientation = 0;
			// from http://jpegclub.org/exif_orientation.html
			// and http://stackoverflow.com/questions/20478765/how-to-get-the-correct-orientation-of-the-image-selected-from-the-default-image
			if( exif_orientation_s == ExifInterface.ORIENTATION_UNDEFINED || exif_orientation_s == ExifInterface.ORIENTATION_NORMAL ) {
				// leave unchanged
			}
			else if( exif_orientation_s == ExifInterface.ORIENTATION_ROTATE_180 ) {
				needs_tf = true;
				exif_orientation = 180;
			}
			else if( exif_orientation_s == ExifInterface.ORIENTATION_ROTATE_90 ) {
				needs_tf = true;
				exif_orientation = 90;
			}
			else if( exif_orientation_s == ExifInterface.ORIENTATION_ROTATE_270 ) {
				needs_tf = true;
				exif_orientation = 270;
			}
			else {
				// just leave unchanged for now
	    		if( MyDebug.LOG )
	    			Log.e(TAG, "    unsupported exif orientation: " + exif_orientation_s);
			}
    		if( MyDebug.LOG )
    			Log.d(TAG, "    exif orientation: " + exif_orientation);

			if( needs_tf ) {
				Matrix m = new Matrix();
				m.setRotate(exif_orientation, bitmap.getWidth() * 0.5f, bitmap.getHeight() * 0.5f);
				Bitmap rotated_bitmap = Bitmap.createBitmap(bitmap, 0, 0,bitmap.getWidth(), bitmap.getHeight(), m, true);
				if( rotated_bitmap != bitmap ) {
					bitmap.recycle();
					bitmap = rotated_bitmap;
				}
			}
		}
		catch(IOException exception) {
			if( MyDebug.LOG )
				Log.e(TAG, "exif orientation ioexception");
			exception.printStackTrace();
		}
		return bitmap;
    }

	private void setGPSDirectionExif(ExifInterface exif, boolean store_geo_direction, double geo_direction) {
    	if( store_geo_direction ) {
			float geo_angle = (float)Math.toDegrees(geo_direction);
			if( geo_angle < 0.0f ) {
				geo_angle += 360.0f;
			}
			if( MyDebug.LOG )
				Log.d(TAG, "save geo_angle: " + geo_angle);
			// see http://www.sno.phy.queensu.ca/~phil/exiftool/TagNames/GPS.html
			String GPSImgDirection_string = Math.round(geo_angle*100) + "/100";
			if( MyDebug.LOG )
				Log.d(TAG, "GPSImgDirection_string: " + GPSImgDirection_string);
		   	exif.setAttribute(TAG_GPS_IMG_DIRECTION, GPSImgDirection_string);
		   	exif.setAttribute(TAG_GPS_IMG_DIRECTION_REF, "M");
    	}
	}

	private void setDateTimeExif(ExifInterface exif) {
    	String exif_datetime = exif.getAttribute(ExifInterface.TAG_DATETIME);
    	if( exif_datetime != null ) {
        	if( MyDebug.LOG )
    			Log.d(TAG, "write datetime tags: " + exif_datetime);
        	exif.setAttribute("DateTimeOriginal", exif_datetime);
        	exif.setAttribute("DateTimeDigitized", exif_datetime);
    	}
	}
	
	private void fixGPSTimestamp(ExifInterface exif) {
		if( MyDebug.LOG )
			Log.d(TAG, "fixGPSTimestamp");
		// hack: problem on Camera2 API (at least on Nexus 6) that if geotagging is enabled, then the resultant image has incorrect Exif TAG_GPS_DATESTAMP (GPSDateStamp) set (tends to be around 2038 - possibly a driver bug of casting long to int?)
		// whilst we don't yet correct for that bug, the more immediate problem is that it also messes up the DATE_TAKEN field in the media store, which messes up Gallery apps
		// so for now, we correct it based on the DATE_ADDED value.
    	// see http://stackoverflow.com/questions/4879435/android-put-gpstimestamp-into-jpg-exif-tags
    	exif.setAttribute(ExifInterface.TAG_GPS_TIMESTAMP, Long.toString(System.currentTimeMillis()));
	}
	
	private boolean needGPSTimestampHack(boolean using_camera2, boolean store_location) {
		if( using_camera2 ) {
    		return store_location;
		}
		return false;
	}

	/** Reads from picFile and writes the contents to saveUri.
	 */
	private void copyFileToUri(Context context, Uri saveUri, File picFile) throws FileNotFoundException, IOException {
		if( MyDebug.LOG ) {
			Log.d(TAG, "copyFileToUri");
			Log.d(TAG, "saveUri: " + saveUri);
			Log.d(TAG, "picFile: " + saveUri);
		}
        InputStream inputStream = null;
	    OutputStream realOutputStream = null;
	    try {
            inputStream = new FileInputStream(picFile);
		    realOutputStream = context.getContentResolver().openOutputStream(saveUri);
		    // Transfer bytes from in to out
		    byte [] buffer = new byte[1024];
		    int len = 0;
		    while( (len = inputStream.read(buffer)) > 0 ) {
		    	realOutputStream.write(buffer, 0, len);
		    }
	    }
	    finally {
	    	if( inputStream != null ) {
	    		inputStream.close();
	    		inputStream = null;
	    	}
	    	if( realOutputStream != null ) {
	    		realOutputStream.close();
	    		realOutputStream = null;
	    	}
	    }
	}
}
