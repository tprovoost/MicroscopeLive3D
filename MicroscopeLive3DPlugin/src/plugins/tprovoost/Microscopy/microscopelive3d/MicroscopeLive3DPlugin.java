package plugins.tprovoost.Microscopy.microscopelive3d;

import icy.gui.component.IcyLogo;
import icy.gui.dialog.ConfirmDialog;
import icy.gui.dialog.MessageDialog;
import icy.gui.frame.IcyFrame;
import icy.gui.frame.progress.AnnounceFrame;
import icy.gui.frame.progress.ToolTipFrame;
import icy.gui.util.GuiUtil;
import icy.image.IcyBufferedImage;
import icy.preferences.IcyPreferences;
import icy.preferences.XMLPreferences;
import icy.sequence.Sequence;
import icy.sequence.SequenceAdapter;
import icy.system.thread.ThreadUtil;
import icy.type.DataType;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.GridLayout;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.AdjustmentEvent;
import java.awt.event.AdjustmentListener;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;

import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollBar;
import javax.swing.JSpinner;
import javax.swing.JTextField;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingConstants;
import javax.swing.event.ChangeEvent;
import javax.swing.event.ChangeListener;

import org.micromanager.utils.StateItem;

import plugins.tprovoost.Microscopy.MicroManagerForIcy.LiveSequence;
import plugins.tprovoost.Microscopy.MicroManagerForIcy.MicroscopeImage;
import plugins.tprovoost.Microscopy.MicroManagerForIcy.MicroscopePluginAcquisition;
import plugins.tprovoost.Microscopy.MicroManagerForIcy.Tools.ImageGetter;
import plugins.tprovoost.Microscopy.MicroManagerForIcy.Tools.MathTools;
import plugins.tprovoost.Microscopy.MicroManagerForIcy.Tools.StageMover;

public class MicroscopeLive3DPlugin extends MicroscopePluginAcquisition
{

    // ------
    // CORE
    // ------
    /** Actual Frame thread. */
    private Live3DThread _thread;
    /**
     * refresh rate value from corresponding combobox. If the refresh rate is
     * higher than the necessary time for capture, will not be considered.
     */
    private double _refreshrate = 0;
    /** Reference to the video */
    private LiveSequence video = null;
    /** Number of slices */
    private int _slices = 10;
    /** Interval between slices. */
    private double _interval_ = 1.0D;
    // -------
    // DISPLAY
    // -------
    private JPanel _panel_scroll;
    private JPanel panel_3d_options;
    private JScrollBar _scrollbar_slices;
    private JLabel _lbl_slices_above;
    private JLabel _lbl_slices_below;
    private JPanel panel_refresh;
    private JTextField _tfRefresh;
    private JTextField _tf_interval;
    private SpinnerNumberModel _model_slices;
    private JButton btn_start;
    private JButton btn_stop;

    // -----------
    // PREFERENCES
    // -----------
    private XMLPreferences _prefs = IcyPreferences.pluginRoot(this);
    private JButton buttonRefreshSequence;
    private static final String PREF_SLICES = "slices";
    private static final String PREF_INTERVAL = "interval";
    private static final String PREF_REFRESH = "refresh";

    @Override
    public void start()
    {
        IcyFrame frame = new IcyFrame("Live 3D Options", true, true, false, true);
        IcyLogo logo = new IcyLogo("Live 3D Options");
        logo.setPreferredSize(new Dimension(200, 80));

        JPanel panel_center = new JPanel();
        btn_start = new JButton("Start");
        btn_start.addActionListener(new ActionListener()
        {

            @Override
            public void actionPerformed(ActionEvent e)
            {
                if (ConfirmDialog.confirm("Are you sure ?",
                        "<html>It will be impossible to manually change the Z position of<br/> "
                                + "the microscope while Live 3D Plugin is running.<br/>"
                                + "<br/>Do you want to continue ?</html>"))
                {
                    // mainGui.continuousAcquisitionNeeded(MicroscopeLive3DPlugin.this);
                    _thread = new Live3DThread();
                    _thread.start();
                }
            }
        });
        btn_stop = new JButton("Stop");
        btn_stop.addActionListener(new ActionListener()
        {

            @Override
            public void actionPerformed(ActionEvent e)
            {
                _thread.stopThread();
            }
        });
        btn_stop.setEnabled(false);

        // ---------------
        // SLICES OPTIONS
        // ---------------
        panel_3d_options = GuiUtil.generatePanel("Slices");
        panel_3d_options.setLayout(new GridLayout(2, 1));
        JPanel panel_slices = new JPanel();
        JPanel panel_interval = new JPanel();

        panel_slices.setLayout(new BoxLayout(panel_slices, BoxLayout.X_AXIS));
        panel_interval.setLayout(new BoxLayout(panel_interval, BoxLayout.X_AXIS));

        _model_slices = new SpinnerNumberModel(10, 1, 1000, 1);
        final JSpinner _spin_slices = new JSpinner(_model_slices);
        _model_slices.addChangeListener(new ChangeListener()
        {

            @Override
            public void stateChanged(ChangeEvent changeevent)
            {
                _slices = _model_slices.getNumber().intValue();
                if (_slices <= 1)
                {
                    setPanelEnabled(_panel_scroll, false);
                    setPanelEnabled(panel_refresh, false);
                }
                else if (!_panel_scroll.isEnabled())
                {
                    setPanelEnabled(_panel_scroll, true);
                    setPanelEnabled(panel_refresh, true);
                }
                _prefs.putInt(PREF_SLICES, _slices);
                _scrollbar_slices.setValues(_slices / 2, _slices, 0, _slices * 2);
                setScrollBarText();
            }
        });

        _tf_interval = new JTextField("1.0", 4);
        _tf_interval.setHorizontalAlignment(JTextField.RIGHT);
        _tf_interval.addKeyListener(new KeyAdapter()
        {
            @Override
            public void keyPressed(KeyEvent keyevent)
            {
                super.keyPressed(keyevent);
                if (keyevent.getKeyCode() == KeyEvent.VK_ENTER)
                    try
                    {
                        _interval_ = Double.valueOf(_tf_interval.getText()).doubleValue();
                        if (video != null)
                            video.setPixelSizeZ(_interval_ / 1000);
                        _prefs.putDouble(PREF_INTERVAL, _interval_);
                    }
                    catch (NumberFormatException e)
                    {
                    }
            }
        });
        panel_slices.add(new JLabel("Slices Count: "));
        panel_slices.add(Box.createHorizontalGlue());
        panel_slices.add(_spin_slices);
        panel_interval.add(new JLabel("Interval (µm): "));
        panel_interval.add(_tf_interval);

        panel_3d_options.add(panel_slices);
        panel_3d_options.add(panel_interval);

        _panel_scroll = GuiUtil.generatePanel("Distribution");
        _panel_scroll.setToolTipText("Choose the way images are taken. Click on \"?\" Button for more information.");
        _panel_scroll.setLayout(new BorderLayout());

        _scrollbar_slices = new JScrollBar(SwingConstants.VERTICAL, _slices / 2, _slices, 0, _slices * 2);
        _scrollbar_slices.setPreferredSize(new Dimension(15, 100));
        _scrollbar_slices.addAdjustmentListener(new AdjustmentListener()
        {

            @Override
            public void adjustmentValueChanged(AdjustmentEvent adjustmentevent)
            {
                setScrollBarText();
            }
        });

        JButton btn_help = new JButton("?");
        btn_help.addActionListener(new ActionListener()
        {

            @Override
            public void actionPerformed(ActionEvent actionevent)
            {
                MessageDialog.showDialog("<html>Choose the way images are taken:<br/>" + ""
                        + "<ul><li>Knot at the bottom = from current Z to higher Zs</li>"
                        + "<li>Knot centered = snap half of images below and half above current Z</li>"
                        + "<li>Knot at the top = from current Z to lower Zs</li></ul></html>");
            }
        });

        JPanel panel_slider_bar = new JPanel();
        panel_slider_bar.setLayout(new BorderLayout());
        panel_slider_bar.add(_scrollbar_slices, BorderLayout.CENTER);

        _lbl_slices_above = new JLabel("" + _slices / 2 + " above");
        _lbl_slices_above.setHorizontalAlignment(SwingConstants.CENTER);
        _lbl_slices_above.setHorizontalTextPosition(SwingConstants.CENTER);
        _lbl_slices_below = new JLabel("" + _slices / 2 + " below");
        _lbl_slices_below.setHorizontalAlignment(SwingConstants.CENTER);
        _lbl_slices_below.setHorizontalTextPosition(SwingConstants.CENTER);

        panel_slider_bar.add(_lbl_slices_above, BorderLayout.NORTH);
        panel_slider_bar.add(_lbl_slices_below, BorderLayout.SOUTH);

        _panel_scroll.add(panel_slider_bar);
        _panel_scroll.add(btn_help, BorderLayout.SOUTH);

        // -------------------
        // OBSERVATION OPTIONS
        // -------------------
        _tfRefresh = new JTextField(6);
        _tfRefresh.setHorizontalAlignment(JTextField.RIGHT);
        _tfRefresh.setText("0");
        _tfRefresh.setToolTipText("Time to wait before another stack is acquired. Zero means continuous acquisition.");
        _tfRefresh.addKeyListener(new KeyAdapter()
        {
            @Override
            public void keyPressed(KeyEvent e)
            {
                super.keyPressed(e);
                if (e.getKeyCode() == KeyEvent.VK_ENTER)
                {
                    Double d = Double.valueOf(_tfRefresh.getText());
                    if (d != null)
                    {
                        try
                        {
                            _refreshrate = d.doubleValue();
                            _prefs.putDouble(PREF_REFRESH, _refreshrate);
                        }
                        catch (Exception e1)
                        {
                            _refreshrate = 0;
                        }
                    }
                }
            }
        });
        panel_refresh = GuiUtil.generatePanel("Observation");
        panel_refresh.setLayout(new BoxLayout(panel_refresh, BoxLayout.Y_AXIS));

        JLabel lblRefresh = new JLabel("Refresh delay (ms):");
        lblRefresh
                .setToolTipText("Time to wait for before another stack is acquired. Zero means continuous acquisition.");

        JPanel panelRefreshNorth = new JPanel();
        panelRefreshNorth.setLayout(new BoxLayout(panelRefreshNorth, BoxLayout.X_AXIS));
        panelRefreshNorth.add(lblRefresh);
        panelRefreshNorth.add(_tfRefresh);

        buttonRefreshSequence = new JButton("Refresh Sequence");
        buttonRefreshSequence.setToolTipText("This option may lead to less fluidity");
        buttonRefreshSequence.addActionListener(new ActionListener()
        {

            @Override
            public void actionPerformed(ActionEvent e)
            {
                if (video != null)
                {
                    video.updateChannelsBounds(true);
                    for (IcyBufferedImage img : video.getAllImage())
                    {
                        img.updateChannelsBounds();
                    }
                }
            }
        });

        panel_refresh.add(panelRefreshNorth);
        panel_refresh.add(buttonRefreshSequence);

        // ------------
        // RUN OPTIONS
        // ------------
        JPanel panel_buttons = GuiUtil.generatePanel("Run");
        panel_buttons.setLayout(new BoxLayout(panel_buttons, BoxLayout.X_AXIS));
        panel_buttons.add(Box.createHorizontalGlue());
        panel_buttons.add(btn_start);
        panel_buttons.add(Box.createHorizontalGlue());
        panel_buttons.add(btn_stop);
        panel_buttons.add(Box.createHorizontalGlue());

        // ----------------
        // DISPLAY
        // ----------------
        panel_center.setLayout(new BoxLayout(panel_center, BoxLayout.X_AXIS));
        JPanel panel_left = new JPanel();
        panel_left.setLayout(new BoxLayout(panel_left, BoxLayout.Y_AXIS));
        panel_left.add(panel_3d_options);
        panel_left.add(panel_refresh);
        panel_left.add(panel_buttons);

        panel_center.add(panel_left);
        panel_center.add(_panel_scroll);

        JPanel mainPanel = new JPanel(new BorderLayout());
        mainPanel.add(logo, BorderLayout.NORTH);
        mainPanel.add(panel_center, BorderLayout.CENTER);

        loadPreferences();
        frame.setLayout(new GridLayout(1, 1));
        frame.add(mainPanel);
        // frame.setSize(new Dimension(300, 300));
        frame.pack();
        frame.setVisible(true);
        frame.addToMainDesktopPane();
        frame.requestFocus();
    }

    void loadPreferences()
    {
        _interval_ = _prefs.getDouble(PREF_INTERVAL, 1);
        _refreshrate = _prefs.getDouble(PREF_REFRESH, 0);
        _slices = _prefs.getInt(PREF_SLICES, 10);

        _model_slices.setValue(_slices);
        _tf_interval.setText("" + _interval_);
        _tfRefresh.setText("" + _refreshrate);
    }

    /**
     * @param img
     * @see createVideo()
     */
    private void createVideo(MicroscopeImage img)
    {
        if (img != null)
        {
            video = new LiveSequence(img);
        }
        else
            video = new LiveSequence();
        video.setName("Live 3D");
        video.setAutoUpdateChannelBounds(false);
        // sets listener on the frame in order to remove this plugin
        // from the GUI when the frame is closed
        video.addListener(new SequenceAdapter()
        {
            @Override
            public void sequenceClosed(Sequence sequence)
            {
                super.sequenceClosed(sequence);
                _thread.stopThread();
                // mainGui.continuousAcquisitionReleased(MicroscopeLive3DPlugin.this);
                mainGui.removePlugin(MicroscopeLive3DPlugin.this);
            }
        });
        addSequence(video);
    }

    /**
     * Recursively enables or disables a panel.
     * 
     * @param panel
     *        : Panel to be Enabled
     * @param enabled
     *        : Flag value
     */
    private void setPanelEnabled(JPanel panel, boolean enabled)
    {
        panel.setEnabled(enabled);
        for (Component c : panel.getComponents())
        {
            if (c instanceof JPanel)
                setPanelEnabled((JPanel) c, enabled);
            else
                c.setEnabled(enabled);
        }
    }

    /**
     * Update scrollbar texts.
     */
    private void setScrollBarText()
    {
        _lbl_slices_above.setText("" + _scrollbar_slices.getValue() + " above");
        _lbl_slices_below.setText("" + (_slices - _scrollbar_slices.getValue()) + " below");
    }

    @Override
    public void notifyConfigAboutToChange(StateItem item) throws Exception
    {
        _thread.pauseThread(true);
    }

    @Override
    public void notifyConfigChanged(StateItem item) throws Exception
    {
        _thread.pauseThread(false);
    }

    @Override
    public void MainGUIClosed()
    {
        _thread.stopThread();
    }

    /**
     * Thread for the live 3D.
     * 
     * @author Thomas Provoost
     */
    class Live3DThread extends Thread
    {

        /** Name of the focus device */
        private String _nameZ;
        /** Used to pause the thread */
        private boolean _please_wait = false;
        /** Stops the thread */
        private boolean _stop = false;
        /** Access boolean to captureStacks */
        private boolean alreadyCapturing = false;
        /** Absolute position of the focus device. */
        private double absoluteZ = 0;

        /**
         * This method will return the Z Stage to its original position, before
         * the thread was started.
         */
        public void backToOriginalPosition()
        {
            try
            {
                mCore.setPosition(_nameZ, absoluteZ);
                mCore.waitForDevice(_nameZ);
            }
            catch (Exception e)
            {
            }
        }

        public synchronized boolean isPaused()
        {
            return !alreadyCapturing;
        }

        @Override
        public void run()
        {
            ThreadUtil.invokeLater(new Runnable()
            {

                @Override
                public void run()
                {
                    btn_stop.setEnabled(true);
                    btn_start.setEnabled(false);
                }
            });
            super.run();
            _nameZ = mCore.getFocusDevice();
            if (_nameZ == null || _nameZ == "")
                return;
            ThreadUtil.invokeLater(new Runnable()
            {

                @Override
                public void run()
                {
                    new ToolTipFrame(
                            "<html><center><b>Tooltip</b><br/></center>To follow the acquisition progress of each stack,<br/>"
                                    + "please refer to the \"Running Acquisitions\" panel<br/>in Micro-Manager For Icy GUI.</html>",
                            getClass().getName() + "t1");
                }
            });
            while (!_stop)
            {
                while (_please_wait)
                {
                    try
                    {
                        sleep(100);
                    }
                    catch (InterruptedException e)
                    {
                    }
                }
                if (_stop)
                    break;
                try
                {
                    // Verify if video is null. if it is, allocation of the
                    // video.
                    if (video == null)
                    {
                        if (alreadyCapturing)
                            continue;
                        if (_slices <= 1)
                        {
                            MicroscopeImage img = ImageGetter.snapImage(mCore);
                            if (img == null)
                                System.out.println("img null !");
                            createVideo(img);
                        }
                        else
                        {
                            new AnnounceFrame("Please wait for the first acquisition.", 5);

                            createVideo(null);
                            for (int i = 0; i < _slices; ++i)
                            {
                                IcyBufferedImage img = new IcyBufferedImage((int) mCore.getImageWidth(),
                                        (int) mCore.getImageHeight(), 1, DataType.USHORT);
                                img.setAutoUpdateChannelBounds(false);
                                // img.updateChannelsBounds();
                                video.addImage(img);
                            }
                            captureStacks(video);
                        }
                    }
                    else
                    {
                        // Verifications on the video dimensions (x,y,z)
                        if (video.getSizeZ() != _slices || video.getWidth() != (int) mCore.getImageWidth()
                                || video.getHeight() != (int) mCore.getImageHeight())
                        {

                            // Throw an exception if image captured has no
                            // width
                            // or height
                            if (mCore.getImageWidth() <= 0 || mCore.getImageHeight() <= 0)
                                throw new InterruptedException();
                            try
                            {
                                video.beginUpdate();
                                video.removeAllImage();
                                if (_slices <= 1)
                                {
                                    video.addImage(ImageGetter.snapImage(mCore));
                                }
                                else
                                {
                                    // Acquisition
                                    if (alreadyCapturing)
                                        continue;
                                    for (int i = 0; i < _slices; ++i)
                                    {
                                        IcyBufferedImage img = new IcyBufferedImage((int) mCore.getImageWidth(),
                                                (int) mCore.getImageHeight(), 1, DataType.USHORT);
                                        img.setAutoUpdateChannelBounds(false);
                                        video.addImage(img);
                                    }
                                    captureStacks(video);
                                }
                            }
                            finally
                            {
                                video.endUpdate();
                            }
                        }
                        else
                        {
                            if (_slices == 1)
                            {
                                video.getImage(0, 0).setDataXYAsShort(0, ImageGetter.snapImageToShort(mCore));
                            }
                            else
                            {
                                try
                                {
                                    video.beginUpdate();
                                    // Acquisition
                                    captureStacks(video);
                                }
                                catch (Exception e)
                                {
                                    e.printStackTrace();
                                }
                                finally
                                {
                                    video.endUpdate();
                                }
                            }
                        }
                        video.notifyListeners();
                    }
                    sleep(10);
                }
                catch (Exception e)
                {
                    break;
                }
            }
            ThreadUtil.invokeLater(new Runnable()
            {

                @Override
                public void run()
                {
                    btn_stop.setEnabled(false);
                    btn_start.setEnabled(true);
                }
            });
            notifyAcquisitionOver();
            for (IcyBufferedImage img : video.getAllImage())
                img.setAutoUpdateChannelBounds(true);
            video.setAutoUpdateChannelBounds(true);
            video = null;
        }

        /**
         * Thread safe method to pause the thread
         * 
         * @param b
         *        : Boolean flag.<br/>
         *        The value "<b>true</b>" will pause the thread,
         *        "<b>false</b>" will resume it.
         */
        synchronized void pauseThread(boolean b)
        {
            _please_wait = b;
            if (_slices <= 1)
            {
                try
                {
                    MathTools.waitFor((long) (mCore.getExposure() + 200));
                }
                catch (Exception e)
                {
                    e.printStackTrace();
                }
            }
            else
            {
                // waiting for 3D the end of the capture
                while (alreadyCapturing)
                {
                    try
                    {
                        Thread.sleep(50);
                    }
                    catch (InterruptedException e)
                    {
                        e.printStackTrace();
                    }
                }
            }
        }

        /**
         * Thread safe method to stop the thread
         */
        synchronized void stopThread()
        {
            _stop = true;
            ThreadUtil.invokeNow(new Runnable()
            {

                @Override
                public void run()
                {
                    btn_stop.setEnabled(false);
                    btn_start.setEnabled(true);
                }
            });
        }

        /**
         * Thread safe method to notify an acquisition is already running. This
         * method prevents the thread from acquiring two stacks at the same
         * time.
         * 
         * @param b
         *        : Boolean flag.
         */
        synchronized void setAlreadyCapturing(boolean b)
        {
            alreadyCapturing = b;
        }

        /**
         * This method will capture every stack according to the parameters:
         * number of slices, interval and distribution
         * 
         * @return Returns an ArrayList of all stacks as IcyBufferedImages.
         */
        void captureStacks(Sequence s)
        {
            notifyAcquisitionStarted(true);
            setAlreadyCapturing(true);
            int wantedSlices = _slices;
            int wantedDistribution = _scrollbar_slices.getValue();
            double wantedInterval = _interval_;
            double supposedLastPosition = 0;
            _nameZ = mCore.getFocusDevice();
            try
            {
                absoluteZ = mCore.getPosition(_nameZ);
                supposedLastPosition = absoluteZ + ((wantedDistribution - 1) * wantedInterval);
                StageMover.moveZRelative(-((wantedSlices - wantedDistribution) * wantedInterval));
                // mCore.waitForDevice(_nameZ);
                s.getImage(0, 0).setDataXYAsShort(0, ImageGetter.snapImageToShort(mCore));
            }
            catch (Exception e)
            {
                new AnnounceFrame("Error wile moving");
                return;
            }
            for (int z = 1; z < _slices; ++z)
            {
                if (_stop)
                    return;
                while (_please_wait)
                {
                    if (alreadyCapturing)
                        setAlreadyCapturing(false);
                    try
                    {
                        Thread.sleep(100);
                    }
                    catch (InterruptedException e1)
                    {
                    }
                }
                if (!alreadyCapturing)
                    setAlreadyCapturing(true);
                try
                {
                    StageMover.moveZRelative(wantedInterval);
                    // mCore.waitForDevice(_nameZ);
                    s.getImage(0, z).setDataXYAsShort(0, ImageGetter.snapImageToShort(mCore));
                }
                catch (Exception e)
                {
                    break;
                }
                double progress = 1D * z / _slices * 100D;
                notifyProgress((int) progress);
            }
            try
            {
                if (absoluteZ != 0)
                {
                    // mCore.waitForDevice(_nameZ);
                    if (supposedLastPosition != 0)
                    {
                        double actualPos = mCore.getPosition(_nameZ);
                        absoluteZ += actualPos - supposedLastPosition;
                    }
                    backToOriginalPosition();
                }
            }
            catch (Exception e)
            {
                new AnnounceFrame("Error while moving");
            }
            setAlreadyCapturing(false);
            notifyAcquisitionOver();
        }
    }

    @Override
    public String getRenderedName()
    {
        return "Live 3D";
    }

}