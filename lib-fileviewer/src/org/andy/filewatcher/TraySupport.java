package org.andy.filewatcher;

import java.awt.AWTException;
import java.awt.MenuItem;
import java.awt.PopupMenu;
import java.awt.SystemTray;
import java.awt.TrayIcon;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.image.BufferedImage;
import java.io.IOException;

import javax.imageio.ImageIO;

public class TraySupport {

	public static void initTray() {

		if (!SystemTray.isSupported()) {
			System.out.println("SystemTray wird nicht unterstützt.");
			return;
		}

		try {
			BufferedImage image = ImageIO.read(TraySupport.class.getResource("/icon.png"));

			PopupMenu popup = new PopupMenu();
			MenuItem exitItem = new MenuItem("terminar");
			exitItem.addActionListener(new ActionListener() {
				@Override
				public void actionPerformed(ActionEvent e) {
					System.exit(0);
				}
			});
			popup.add(exitItem);

			TrayIcon trayIcon = new TrayIcon(image, "FileWatcher ativo", popup);
			trayIcon.setImageAutoSize(true);

			SystemTray.getSystemTray().add(trayIcon);
			trayIcon.displayMessage("auratronic FileMover", "FileMover iniciado", TrayIcon.MessageType.INFO);

		} catch (IOException | AWTException e) {
			e.printStackTrace();
		}

	}

}
