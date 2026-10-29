package com.rey.editor;

import android.app.*;import android.os.*;import android.content.*;import android.graphics.*;import android.graphics.drawable.ColorDrawable;import android.net.Uri;import android.provider.Settings;import android.view.*;import android.widget.*;import java.io.*;import java.util.*;

public class MainActivity extends Activity {
  StudioView studio;
  @Override public void onCreate(Bundle b){super.onCreate(b); studio=new StudioView(this); setContentView(studio);}
  void importImage(){Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT);i.setType("image/png");i.addCategory(Intent.CATEGORY_OPENABLE);startActivityForResult(i,10);}
  @Override protected void onActivityResult(int r,int c,Intent d){super.onActivityResult(r,c,d);if(r==10&&c==RESULT_OK&&d!=null)try{Bitmap b=BitmapFactory.decodeStream(getContentResolver().openInputStream(d.getData()));if(b!=null)studio.replace(b);}catch(Exception e){studio.message("Could not import PNG");}}
  void exportImage(){try{File f=new File(getExternalFilesDir(null),"rey-editor.png");FileOutputStream o=new FileOutputStream(f);studio.composite().compress(Bitmap.CompressFormat.PNG,100,o);o.close();studio.message("Exported: "+f.getName());}catch(Exception e){studio.message("Export failed");}}
  void message(String s){Toast.makeText(this,s,Toast.LENGTH_SHORT).show();}

  static class Layer {String name; Bitmap bmp; boolean visible=true; float opacity=1; Layer(String n,Bitmap b){name=n;bmp=b;}}
  class StudioView extends View {
    Paint p=new Paint(3); ArrayList<Layer> layers=new ArrayList<>(); ArrayDeque<Bitmap> undo=new ArrayDeque<>(),redo=new ArrayDeque<>(); int selected=0; float zoom=1, panX=0,panY=0; float lastX,lastY; boolean drawing;
    int dark=Color.rgb(12,15,22), panel=Color.rgb(24,29,40), accent=Color.rgb(124,156,255); Bitmap base;
    StudioView(Context c){super(c);setFocusable(true); newDocument(1200,800);}
    void newDocument(int w,int h){base=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888);base.eraseColor(Color.WHITE);layers.clear();layers.add(new Layer("Background",base));selected=0;invalidate();}
    void replace(Bitmap b){newDocument(b.getWidth(),b.getHeight());layers.set(0,new Layer("Imported PNG",b));base=b;invalidate();}
    Bitmap composite(){Bitmap out=Bitmap.createBitmap(base.getWidth(),base.getHeight(),Bitmap.Config.ARGB_8888);Canvas c=new Canvas(out);c.drawColor(Color.TRANSPARENT);for(Layer l:layers)if(l.visible){p.setAlpha((int)(l.opacity*255));c.drawBitmap(l.bmp,0,0,p);}p.setAlpha(255);return out;}
    void snapshot(){undo.push(composite());if(undo.size()>20)undo.removeLast();redo.clear();}
    void restore(Bitmap b){if(b==null)return;layers.set(0,new Layer("Composite",b));for(int i=layers.size()-1;i>0;i--)layers.remove(i);invalidate();}
    void addLayer(){snapshot();Bitmap b=Bitmap.createBitmap(base.getWidth(),base.getHeight(),Bitmap.Config.ARGB_8888);layers.add(new Layer("Layer "+layers.size(),b));selected=layers.size()-1;invalidate();}
    void undo(){if(undo.isEmpty())return;redo.push(composite());restore(undo.pop());}
    void redo(){if(redo.isEmpty())return;undo.push(composite());restore(redo.pop());}
    void message(String s){MainActivity.this.message(s);}
    protected void onDraw(Canvas c){super.onDraw(c);c.drawColor(dark);float left=72,right=getWidth()-260,top=64,bottom=getHeight()-24; p.setColor(panel);c.drawRect(0,0,getWidth(),56,p);c.drawRect(0,56,64,getHeight(),p);c.drawRect(right,56,getWidth(),getHeight(),p);
      p.setColor(Color.rgb(40,46,60));c.drawRect(left,top,right,bottom,p);float scale=Math.min((right-left-30f)/base.getWidth(),(bottom-top-30f)/base.getHeight())*zoom;float x=left+(right-left-base.getWidth()*scale)/2+panX,y=top+(bottom-top-base.getHeight()*scale)/2+panY;c.save();c.translate(x,y);c.scale(scale,scale);c.drawBitmap(composite(),0,0,p);c.restore();
      text(c,"REY EDITOR",16,28,Color.WHITE,18);button(c,"Import",105,28,70);button(c,"Export",180,28,70);button(c,"Undo",265,28,62);button(c,"Redo",335,28,62);button(c,"+ Layer",right+18,88,90);text(c,"LAYERS",right+18,140,Color.LTGRAY,12);
      for(int i=layers.size()-1;i>=0;i--){Layer l=layers.get(i);float yy=160+(layers.size()-1-i)*54;p.setColor(i==selected?Color.rgb(55,70,100):Color.rgb(31,37,50));c.drawRoundRect(right+10,yy-24,getWidth()-10,yy+22,8,8,p);text(c,l.visible?"◉":"○",right+22,yy+5,Color.WHITE,16);text(c,l.name,right+48,yy+1,Color.WHITE,13);text(c,Math.round(l.opacity*100)+"%",right+48,yy+18,Color.LTGRAY,10);}
      text(c,"BRUSH",18,92,Color.WHITE,12);text(c,"ERASER",18,140,Color.LTGRAY,11);text(c,"EYE",18,188,Color.LTGRAY,11);text(c,"Zoom "+Math.round(zoom*100)+"%",18,getHeight()-32,Color.LTGRAY,11);
    }
    void text(Canvas c,String s,float x,float y,int col,float size){p.setColor(col);p.setTextSize(size);p.setTypeface(Typeface.DEFAULT);c.drawText(s,x,y,p);} void button(Canvas c,String s,float x,float y,float w){p.setColor(Color.rgb(48,58,78));c.drawRoundRect(x,y-20,x+w,y+16,8,8,p);text(c,s,x+10,y+4,Color.WHITE,12);}
    public boolean onTouchEvent(android.view.MotionEvent e){float x=e.getX(),y=e.getY();if(e.getAction()==0){lastX=x;lastY=y; if(y<56){if(x>100&&x<175)importImage();else if(x>178&&x<255)exportImage();else if(x>260&&x<332)undo();else if(x>335&&x<405)redo();return true;}if(x>getWidth()-260&&y>56&&y<130){addLayer();return true;} if(x<64&&y>55){message("Brush active");return true;} if(x>getWidth()-260&&y>140){int row=(int)((y-136)/54);int idx=layers.size()-1-row;if(idx>=0&&idx<layers.size()){selected=idx;invalidate();}return true;}drawing=true;snapshot();return true;} if(e.getAction()==2&&drawing){float dx=x-lastX,dy=y-lastY;float scale=Math.min((getWidth()-332f)/base.getWidth(),(getHeight()-94f)/base.getHeight())*zoom;Canvas cc=new Canvas(layers.get(selected).bmp);p.setColor(Color.BLACK);p.setStrokeWidth(12/scale);p.setStrokeCap(Paint.Cap.ROUND);cc.drawLine(lastX, lastY, x,y,p);lastX=x;lastY=y;invalidate();return true;} if(e.getAction()==1){drawing=false;return true;}return true;}
  }
}
