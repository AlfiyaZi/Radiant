package radiant.engine;

import java.util.ArrayList;
import java.util.List;

import org.lwjgl.input.Keyboard;

import radiant.assets.AssetLoader;
import radiant.assets.scene.Scene;
import radiant.engine.core.diag.Clock;
import radiant.engine.core.diag.Log;
import radiant.engine.core.errors.RadiantException;

public abstract class BaseGame {
	/* System */
	private Window window = new Window();
	
	private Renderer forward = new ForwardRenderer();
	private Renderer deferred = new DeferredRenderer();
	
	/* Scene */
	private List<Scene> scenes = new ArrayList<Scene>();
	protected Scene currentScene = null;
	
	/* Game loop */
	private int maxSkip = 15;
	private int skipTime = 40;
	private int framesPerSecond = 0;
	
	private boolean deferredOn = false;
	
	public final void startGame() throws RadiantException {
		if(AssetLoader.getErrors() > 0) {
			throw new RadiantException("Can't start game, there are unresolved errors");
		}
		forward.create();
		deferred.create();
		
		update();
	}
	
	public final void loadWindow(String title, int width, int height) throws RadiantException {
		window.create(title, width, height);
	}
	
	public void shutdown() {
		window.destroy();
	}
	
	Clock clock = new Clock();
	
	public void update() {
		long nextUpdate = System.currentTimeMillis();
		long lastFpsCount = System.currentTimeMillis();
		
		int frames = 0;
		
		while(!window.isClosed()) {
			if(currentScene != null) {
				int skipped = 0;
				
				//Count the FPS
			    if (System.currentTimeMillis() - lastFpsCount > 1000) {
			      lastFpsCount = System.currentTimeMillis();
			      framesPerSecond = frames;
			      Log.debug("FPS: " + framesPerSecond);
			      frames = 0;
			    }
			    
			    // Switch between renderers
			    deferredOn = Keyboard.isKeyDown(Keyboard.KEY_2);
			    
				while(System.currentTimeMillis() > nextUpdate && skipped < maxSkip) {
					currentScene.update();
					nextUpdate += skipTime;
					skipped++;
				}
				
				if (deferredOn) {
					forward.update();
				}
				else {
					deferred.update();
				}

				window.update();

				frames++;
			}
		}
		shutdown();
	}
	
	public void setUpdateRate(int updateRate) {
		skipTime = 1000 / updateRate;
	}
	
	/* Window */
	public void setTitle(String title) {
		window.setTitle(title);
	}
	
	public void setSize(int width, int height) {
		window.setSize(width, height);
	}
	
	/* Scene */
	public void addScene(Scene scene) {
		scenes.add(scene);
	}
	
	public void loadScene() {
		
	}
	
	public Scene getScene() {
		return currentScene;
	}
	
	public void setScene(Scene scene) {
		for(Scene s: scenes) {
			if(s == scene) {
				currentScene = s;
				forward.setScene(currentScene);
				deferred.setScene(currentScene);
				currentScene.start();
			}
		}
	}
	
	/* Game loop */
	public int getFps() {
		return framesPerSecond;
	}
}
