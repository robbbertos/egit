/*******************************************************************************
 * Copyright (c) 2010, 2013 SAP AG and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *    Mathias Kinzler (SAP AG) - initial implementation
 *******************************************************************************/
package org.eclipse.egit.ui;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import org.eclipse.core.commands.ExecutionException;
import org.eclipse.core.commands.NotEnabledException;
import org.eclipse.core.commands.NotHandledException;
import org.eclipse.core.commands.common.NotDefinedException;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.Path;
import org.eclipse.core.variables.IStringVariableManager;
import org.eclipse.core.variables.VariablesPlugin;
import org.eclipse.egit.ui.internal.RepositorySaveableFilter;
import org.eclipse.egit.ui.internal.UIIcons;
import org.eclipse.egit.ui.internal.UIText;
import org.eclipse.egit.ui.internal.components.RefContentProposal;
import org.eclipse.jface.action.MenuManager;
import org.eclipse.jface.bindings.keys.KeyStroke;
import org.eclipse.jface.bindings.keys.ParseException;
import org.eclipse.jface.dialogs.IDialogSettings;
import org.eclipse.jface.fieldassist.ContentProposalAdapter;
import org.eclipse.jface.fieldassist.ControlDecoration;
import org.eclipse.jface.fieldassist.FieldDecorationRegistry;
import org.eclipse.jface.fieldassist.IContentProposal;
import org.eclipse.jface.fieldassist.IContentProposalProvider;
import org.eclipse.jface.fieldassist.TextContentAdapter;
import org.eclipse.jface.resource.FontRegistry;
import org.eclipse.jface.resource.ImageDescriptor;
import org.eclipse.jface.resource.ResourceManager;
import org.eclipse.jface.text.IRegion;
import org.eclipse.jface.text.ITextViewer;
import org.eclipse.jface.text.Region;
import org.eclipse.jface.text.hyperlink.IHyperlink;
import org.eclipse.jface.text.hyperlink.IHyperlinkDetector;
import org.eclipse.jface.viewers.AbstractTreeViewer;
import org.eclipse.jface.viewers.Viewer;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.osgi.util.NLS;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.StyleRange;
import org.eclipse.swt.events.DisposeEvent;
import org.eclipse.swt.events.DisposeListener;
import org.eclipse.swt.events.KeyEvent;
import org.eclipse.swt.events.SelectionAdapter;
import org.eclipse.swt.events.SelectionEvent;
import org.eclipse.swt.graphics.Font;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.graphics.ImageData;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.graphics.Resource;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Text;
import org.eclipse.swt.widgets.ToolBar;
import org.eclipse.swt.widgets.ToolItem;
import org.eclipse.swt.widgets.Widget;
import org.eclipse.ui.ISharedImages;
import org.eclipse.ui.IWorkbench;
import org.eclipse.ui.IWorkbenchCommandConstants;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.actions.ContributionItemFactory;
import org.eclipse.ui.handlers.IHandlerService;
import org.eclipse.ui.keys.IBindingService;

/**
 * Some utilities for UI code
 */
public class UIUtils {
	/**
	 * these activate the content assist; alphanumeric, space plus some expected
	 * special chars
	 */
	private static final char[] VALUE_HELP_ACTIVATIONCHARS = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123457890*@ <>".toCharArray(); //$NON-NLS-1$

	/**
	 * A keystroke for a "submit" action, see {@link #isSubmitKeyEvent(KeyEvent)}
	 */
	public static final KeyStroke SUBMIT_KEY_STROKE = KeyStroke.getInstance(SWT.MOD1, SWT.CR);

	/**
	 * Handles a "previously used values" content assist.
	 * <p>
	 * Adding this to a text field will enable "content assist" by keeping track
	 * of the previously used valued for this field. The previously used values
	 * will be shown in the order they were last used (most recently used ones
	 * coming first in the list) and the number of entries is limited.
	 * <p>
	 * A "bulb" decorator will indicate that content assist is available for the
	 * field, and a tool tip is provided giving more information.
	 * <p>
	 * Content assist is activated by either typing in the field or by using a
	 * dedicated key stroke which is indicated in the tool tip. The list will be
	 * filtered with the content already in the text field with '*' being usable
	 * as wild card.
	 * <p>
	 * Note that the application must issue a call to {@link #updateProposals()}
	 * in order to add a new value to the "previously used values" list.
	 * <p>
	 * The list will be persisted in the plug-in dialog settings.
	 *
	 * @noextend not to be extended by clients
	 * @noimplement not to be implemented by clients, use
	 *              {@link UIUtils#addPreviousValuesContentProposalToText(Text, String)}
	 *              to create instances of this
	 */
	public interface IPreviousValueProposalHandler {
		/**
		 * Updates the proposal list from the value in the text field.
		 * <p>
		 * The value will be truncated to the first 2000 characters in order to
		 * limit data size.
		 * <p>
		 * Note that this must be called in the UI thread, since it accesses the
		 * text field.
		 * <p>
		 * If the value is already in the list, it will become the first entry,
		 * otherwise it will be added at the beginning. Note that empty Strings
		 * will not be stored. The length of the list is limited, and the
		 * "oldest" entries will be removed once the limit is exceeded.
		 * <p>
		 * This call should only be issued if the value in the text field is
		 * "valid" in terms of the application.
		 */
		public void updateProposals();
	}

	/**
	 * Used for
	 * {@link UIUtils#addRefContentProposalToText(Text, Repository, IRefListProvider)}
	 */
	public interface IRefListProvider {
		/**
		 * @return the List of {@link Ref}s to propose
		 */
		public List<Ref> getRefList();
	}

	/**
	 * @param id
	 *            see {@link FontRegistry#get(String)}
	 * @return the font
	 */
	public static Font getFont(final String id) {
		return PlatformUI.getWorkbench().getThemeManager().getCurrentTheme()
				.getFontRegistry().get(id);
	}

	/**
	 * @param id
	 *            see {@link FontRegistry#getBold(String)}
	 * @return the font
	 */
	public static Font getBoldFont(final String id) {
		return PlatformUI.getWorkbench().getThemeManager().getCurrentTheme()
				.getFontRegistry().getBold(id);
	}

	/**
	 * @param id
	 *            see {@link FontRegistry#getItalic(String)}
	 * @return the font
	 */
	public static Font getItalicFont(final String id) {
		return PlatformUI.getWorkbench().getThemeManager().getCurrentTheme()
				.getFontRegistry().getItalic(id);
	}

	/**
	 * Adds little bulb decoration to given control. Bulb will appear in top
	 * left corner of control after giving focus for this control.
	 *
	 * After clicking on bulb image text from <code>tooltip</code> will appear.
	 *
	 * @param control
	 *            instance of {@link Control} object with should be decorated
	 * @param tooltip
	 *            text value which should appear after clicking on bulb image.
	 */
	public static void addBulbDecorator(final Control control,
			final String tooltip) {
		ControlDecoration dec = new ControlDecoration(control, SWT.TOP
				| SWT.LEFT);

		dec.setImage(FieldDecorationRegistry.getDefault().getFieldDecoration(
				FieldDecorationRegistry.DEC_CONTENT_PROPOSAL).getImage());

		dec.setShowOnlyOnFocus(true);
		dec.setShowHover(true);

		dec.setDescriptionText(tooltip);
	}

	/**
	 * Adds a "previously used values" content proposal handler to a text field.
	 * <p>
	 * The keyboard shortcut will be "M1+SPACE" and the list will be limited to
	 * 10 values.
	 *
	 * @param textField
	 *            the text field
	 * @param preferenceKey
	 *            the key under which to store the "previously used values" in
	 *            the dialog settings
	 * @return the handler the proposal handler
	 */
	public static IPreviousValueProposalHandler addPreviousValuesContentProposalToText(
			final Text textField, final String preferenceKey) {
		KeyStroke stroke;
		try {
			stroke = KeyStroke.getInstance("M1+SPACE"); //$NON-NLS-1$
			addBulbDecorator(textField, NLS.bind(
					UIText.UIUtils_PressShortcutMessage, stroke.format()));
		} catch (ParseException e1) {
			Activator.handleError(e1.getMessage(), e1, false);
			stroke = null;
			addBulbDecorator(textField,
					UIText.UIUtils_StartTypingForPreviousValuesMessage);
		}

		IContentProposalProvider cp = new IContentProposalProvider() {

			public IContentProposal[] getProposals(String contents, int position) {

				List<IContentProposal> resultList = new ArrayList<IContentProposal>();

				// make the simplest possible pattern check: allow "*"
				// for multiple characters
				String patternString = contents;
				// ignore spaces in the beginning
				while (patternString.length() > 0
						&& patternString.charAt(0) == ' ') {
					patternString = patternString.substring(1);
				}

				// we quote the string as it may contain spaces
				// and other stuff colliding with the Pattern
				patternString = Pattern.quote(patternString);

				patternString = patternString.replaceAll("\\x2A", ".*"); //$NON-NLS-1$ //$NON-NLS-2$

				// make sure we add a (logical) * at the end
				if (!patternString.endsWith(".*")) { //$NON-NLS-1$
					patternString = patternString + ".*"; //$NON-NLS-1$
				}

				// let's compile a case-insensitive pattern (assumes ASCII only)
				Pattern pattern;
				try {
					pattern = Pattern.compile(patternString,
							Pattern.CASE_INSENSITIVE);
				} catch (PatternSyntaxException e) {
					pattern = null;
				}

				String[] proposals = org.eclipse.egit.ui.Activator.getDefault()
						.getDialogSettings().getArray(preferenceKey);

				if (proposals != null)
					for (final String uriString : proposals) {

						if (pattern != null
								&& !pattern.matcher(uriString).matches())
							continue;

						IContentProposal propsal = new IContentProposal() {

							public String getLabel() {
								return null;
							}

							public String getDescription() {
								return null;
							}

							public int getCursorPosition() {
								return 0;
							}

							public String getContent() {
								return uriString;
							}
						};
						resultList.add(propsal);
					}

				return resultList.toArray(new IContentProposal[resultList
						.size()]);
			}
		};

		ContentProposalAdapter adapter = new ContentProposalAdapter(textField,
				new TextContentAdapter(), cp, stroke,
				VALUE_HELP_ACTIVATIONCHARS);
		// set the acceptance style to always replace the complete content
		adapter
				.setProposalAcceptanceStyle(ContentProposalAdapter.PROPOSAL_REPLACE);

		return new IPreviousValueProposalHandler() {
			public void updateProposals() {
				String value = textField.getText();
				// don't store empty values
				if (value.length() > 0) {
					// we don't want to save too much in the preferences
					if (value.length() > 2000) {
						value = value.substring(0, 1999);
					}
					// now we need to mix the value into the list
					IDialogSettings settings = org.eclipse.egit.ui.Activator
							.getDefault().getDialogSettings();
					String[] existingValues = settings.getArray(preferenceKey);
					if (existingValues == null) {
						existingValues = new String[] { value };
						settings.put(preferenceKey, existingValues);
					} else {

						List<String> values = new ArrayList<String>(
								existingValues.length + 1);

						for (String existingValue : existingValues)
							values.add(existingValue);
						// if it is already the first value, we don't need to do
						// anything
						if (values.indexOf(value) == 0)
							return;

						values.remove(value);
						// we insert at the top
						values.add(0, value);
						// make sure to not store more than the maximum number
						// of values
						while (values.size() > 10)
							values.remove(values.size() - 1);

						settings.put(preferenceKey, values
								.toArray(new String[values.size()]));
					}
				}
			}
		};
	}

	/**
	 * Adds a content proposal for {@link Ref}s (branches, tags...) to a text
	 * field
	 *
	 * @param textField
	 *            the text field
	 * @param repository
	 *            the repository
	 * @param refListProvider
	 *            provides the {@link Ref}s to show in the proposal
	 */
	public static final void addRefContentProposalToText(final Text textField,
			final Repository repository, final IRefListProvider refListProvider) {
		KeyStroke stroke;
		try {
			stroke = KeyStroke.getInstance("M1+SPACE"); //$NON-NLS-1$
			UIUtils.addBulbDecorator(textField, NLS.bind(
					UIText.UIUtils_PressShortcutMessage, stroke.format()));
		} catch (ParseException e1) {
			Activator.handleError(e1.getMessage(), e1, false);
			stroke = null;
			UIUtils.addBulbDecorator(textField,
					UIText.UIUtils_StartTypingForPreviousValuesMessage);
		}

		IContentProposalProvider cp = new IContentProposalProvider() {
			public IContentProposal[] getProposals(String contents, int position) {
				List<IContentProposal> resultList = new ArrayList<IContentProposal>();

				// make the simplest possible pattern check: allow "*"
				// for multiple characters
				String patternString = contents;
				// ignore spaces in the beginning
				while (patternString.length() > 0
						&& patternString.charAt(0) == ' ') {
					patternString = patternString.substring(1);
				}

				// we quote the string as it may contain spaces
				// and other stuff colliding with the Pattern
				patternString = Pattern.quote(patternString);

				patternString = patternString.replaceAll("\\x2A", ".*"); //$NON-NLS-1$ //$NON-NLS-2$

				// make sure we add a (logical) * at the end
				if (!patternString.endsWith(".*")) { //$NON-NLS-1$
					patternString = patternString + ".*"; //$NON-NLS-1$
				}

				// let's compile a case-insensitive pattern (assumes ASCII only)
				Pattern pattern;
				try {
					pattern = Pattern.compile(patternString,
							Pattern.CASE_INSENSITIVE);
				} catch (PatternSyntaxException e) {
					pattern = null;
				}

				List<Ref> proposals = refListProvider.getRefList();

				if (proposals != null)
					for (final Ref ref : proposals) {
						final String shortenedName = Repository
								.shortenRefName(ref.getName());
						if (pattern != null
								&& !pattern.matcher(ref.getName()).matches()
								&& !pattern.matcher(shortenedName).matches())
							continue;

						IContentProposal propsal = new RefContentProposal(
								repository, ref);
						resultList.add(propsal);
					}

				return resultList.toArray(new IContentProposal[resultList
						.size()]);
			}
		};

		ContentProposalAdapter adapter = new ContentProposalAdapter(textField,
				new TextContentAdapter(), cp, stroke,
				UIUtils.VALUE_HELP_ACTIVATIONCHARS);
		// set the acceptance style to always replace the complete content
		adapter
				.setProposalAcceptanceStyle(ContentProposalAdapter.PROPOSAL_REPLACE);
	}

	/**
	 * Set enabled state of the control and all its children
	 * @param control
	 * @param enable
	 */
	public static void setEnabledRecursively(final Control control,
			final boolean enable) {
		control.setEnabled(enable);
		if (control instanceof Composite)
			for (final Control child : ((Composite) control).getChildren())
				setEnabledRecursively(child, enable);
	}

	/**
	 * Dispose of the resource when the widget is disposed
	 *
	 * @param widget
	 * @param resource
	 */
	public static void hookDisposal(Widget widget, final Resource resource) {
		if (widget == null || resource == null)
			return;

		widget.addDisposeListener(new DisposeListener() {

			public void widgetDisposed(DisposeEvent e) {
				resource.dispose();
			}
		});
	}

	/**
	 * Dispose of the resource manager when the widget is disposed
	 *
	 * @param widget
	 * @param resources
	 */
	public static void hookDisposal(Widget widget,
			final ResourceManager resources) {
		if (widget == null || resources == null)
			return;

		widget.addDisposeListener(new DisposeListener() {

			public void widgetDisposed(DisposeEvent e) {
				resources.dispose();
			}
		});
	}

	/**
	 * Get editor image for path
	 *
	 * @param path
	 * @return image descriptor
	 */
	public static ImageDescriptor getEditorImage(final String path) {
		if (path != null && path.length() > 0) {
			final String name = new Path(path).lastSegment();
			if (name != null)
				return PlatformUI.getWorkbench().getEditorRegistry()
						.getImageDescriptor(name);
		}
		return PlatformUI.getWorkbench().getSharedImages()
				.getImageDescriptor(ISharedImages.IMG_OBJ_FILE);
	}

	/**
	 * Get size of image descriptor as point.
	 *
	 * @param descriptor
	 * @return size
	 */
	public static Point getSize(ImageDescriptor descriptor) {
		ImageData data = descriptor.getImageData();
		if (data == null)
			return new Point(0, 0);
		return new Point(data.width, data.height);
	}

	/**
	 * Add expand all and collapse all toolbar items to the given toolbar bound
	 * to the given tree viewer
	 *
	 * @param toolbar
	 * @param viewer
	 * @return given toolbar
	 */
	public static ToolBar addExpansionItems(final ToolBar toolbar,
			final AbstractTreeViewer viewer) {
		ToolItem collapseItem = new ToolItem(toolbar, SWT.PUSH);
		Image collapseImage = UIIcons.COLLAPSEALL.createImage();
		UIUtils.hookDisposal(collapseItem, collapseImage);
		collapseItem.setImage(collapseImage);
		collapseItem.setToolTipText(UIText.UIUtils_CollapseAll);
		collapseItem.addSelectionListener(new SelectionAdapter() {

			public void widgetSelected(SelectionEvent e) {
				viewer.collapseAll();
			}

		});

		ToolItem expandItem = new ToolItem(toolbar, SWT.PUSH);
		Image expandImage = UIIcons.EXPAND_ALL.createImage();
		UIUtils.hookDisposal(expandItem, expandImage);
		expandItem.setImage(expandImage);
		expandItem.setToolTipText(UIText.UIUtils_ExpandAll);
		expandItem.addSelectionListener(new SelectionAdapter() {

			public void widgetSelected(SelectionEvent e) {
				viewer.expandAll();
			}

		});
		return toolbar;
	}

	/**
	 * Get dialog bound settings for given class using standard section name
	 *
	 * @param clazz
	 * @return dialog setting
	 */
	public static IDialogSettings getDialogBoundSettings(final Class<?> clazz) {
		return getDialogSettings(clazz.getName() + ".dialogBounds"); //$NON-NLS-1$
	}

	/**
	 * Get dialog settings for given section name
	 *
	 * @param sectionName
	 * @return dialog settings
	 */
	public static IDialogSettings getDialogSettings(final String sectionName) {
		IDialogSettings settings = Activator.getDefault().getDialogSettings();
		IDialogSettings section = settings.getSection(sectionName);
		if (section == null)
			section = settings.addNewSection(sectionName);
		return section;
	}

	/**
	 * @return The default repository directory as configured in the
	 *         preferences, with variables substituted. An empty string if there
	 *         was an error during substitution.
	 */
	public static String getDefaultRepositoryDir() {
		String dir = Activator.getDefault().getPreferenceStore()
				.getString(UIPreferences.DEFAULT_REPO_DIR);
		IStringVariableManager manager = VariablesPlugin.getDefault()
				.getStringVariableManager();
		try {
			return manager.performStringSubstitution(dir);
		} catch (CoreException e) {
			return ""; //$NON-NLS-1$
		}
	}

	/**
	 * Is viewer in a usable state?
	 *
	 * @param viewer
	 * @return true if usable, false if null or underlying control is null or
	 *         disposed
	 */
	public static boolean isUsable(final Viewer viewer) {
		return viewer != null && isUsable(viewer.getControl());
	}

	/**
	 * Is control usable?
	 *
	 * @param control
	 * @return true if usable, false if null or disposed
	 */
	public static boolean isUsable(final Control control) {
		return control != null && !control.isDisposed();
	}

	/**
	 * Run command with specified id
	 *
	 * @param service
	 * @param id
	 */
	public static void executeCommand(IHandlerService service, String id) {
		executeCommand(service, id, null);
	}

	/**
	 * Run command with specified id
	 *
	 * @param service
	 * @param id
	 * @param event
	 */
	public static void executeCommand(IHandlerService service, String id,
			Event event) {
		try {
			service.executeCommand(id, event);
		} catch (ExecutionException e) {
			Activator.handleError(e.getMessage(), e, false);
		} catch (NotDefinedException e) {
			Activator.handleError(e.getMessage(), e, false);
		} catch (NotEnabledException e) {
			Activator.handleError(e.getMessage(), e, false);
		} catch (NotHandledException e) {
			Activator.handleError(e.getMessage(), e, false);
		}
	}

	/**
	 * Determine if the key event represents a "submit" action
	 * (&lt;modifier&gt;+Enter).
	 *
	 * @param event
	 * @return true, if it means submit, false otherwise
	 */
	public static boolean isSubmitKeyEvent(KeyEvent event) {
		return (event.stateMask & SWT.MODIFIER_MASK) != 0
				&& event.keyCode == SUBMIT_KEY_STROKE.getNaturalKey();
	}

	/**
	 * Prompt for saving all dirty editors for resources in the working
	 * directory of the specified repository.
	 *
	 * @param repository
	 * @return true, if the user opted to continue, false otherwise
	 * @see IWorkbench#saveAllEditors(boolean)
	 */
	public static boolean saveAllEditors(Repository repository) {
		IWorkbench workbench = PlatformUI.getWorkbench();
		IWorkbenchWindow window = workbench.getActiveWorkbenchWindow();
		return workbench.saveAll(window, window, new RepositorySaveableFilter(repository), true);
	}

	/**
	 * @param workbenchWindow the workbench window to use for creating the show in menu.
	 * @return the show in menu
	 */
	public static MenuManager createShowInMenu(IWorkbenchWindow workbenchWindow) {
		MenuManager showInSubMenu = new MenuManager(getShowInMenuLabel());
		showInSubMenu.add(ContributionItemFactory.VIEWS_SHOW_IN.create(workbenchWindow));
		return showInSubMenu;
	}

	/**
	 * Use hyperlink detectors to find a text viewer's hyperlinks and return the
	 * style ranges to render them.
	 *
	 * @param textViewer
	 * @param hyperlinkDetectors
	 * @return the style ranges to render the detected hyperlinks
	 */
	public static StyleRange[] getHyperlinkDetectorStyleRanges(
			ITextViewer textViewer, IHyperlinkDetector[] hyperlinkDetectors) {
		List<StyleRange> styleRangeList = new ArrayList<StyleRange>();
		if (hyperlinkDetectors != null && hyperlinkDetectors.length > 0) {
			for (int i = 0; i < textViewer.getTextWidget().getText().length(); i++) {
				IRegion region = new Region(i, 0);
				for (IHyperlinkDetector hyperLinkDetector : hyperlinkDetectors) {
					IHyperlink[] hyperlinks = hyperLinkDetector
							.detectHyperlinks(textViewer, region, true);
					if (hyperlinks != null) {
						for (IHyperlink hyperlink : hyperlinks) {
							StyleRange hyperlinkStyleRange = new StyleRange(
									hyperlink.getHyperlinkRegion().getOffset(),
									hyperlink.getHyperlinkRegion().getLength(),
									Display.getDefault().getSystemColor(
											SWT.COLOR_BLUE), Display
											.getDefault().getSystemColor(
													SWT.COLOR_WHITE));
							hyperlinkStyleRange.underline = true;
							styleRangeList.add(hyperlinkStyleRange);
						}
					}
				}
			}
		}
		StyleRange[] styleRangeArray = new StyleRange[styleRangeList.size()];
		styleRangeList.toArray(styleRangeArray);
		return styleRangeArray;
	}

	private static String getShowInMenuLabel() {
		IBindingService bindingService = (IBindingService) PlatformUI
				.getWorkbench().getAdapter(IBindingService.class);
		if (bindingService != null) {
			String keyBinding = bindingService
					.getBestActiveBindingFormattedFor(IWorkbenchCommandConstants.NAVIGATE_SHOW_IN_QUICK_MENU);
			if (keyBinding != null)
				return UIText.UIUtils_ShowInMenuLabel + '\t' + keyBinding;
		}

		return UIText.UIUtils_ShowInMenuLabel;
	}
}
