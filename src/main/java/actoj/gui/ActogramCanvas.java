package actoj.gui;

import ij.IJ;
import ij.gui.Plot;
import ij.util.Tools;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.event.MouseEvent;
import java.awt.event.MouseListener;
import java.awt.event.MouseMotionListener;
import java.awt.image.BufferedImage;
import java.text.DecimalFormat;
import java.util.ArrayList;

import javax.swing.JPanel;

import actoj.AverageActivity;
import actoj.activitypattern.Acrophase;
import actoj.activitypattern.OnOffset;
import actoj.core.Actogram;
import actoj.core.ExternalVariable;
import actoj.core.MarkerList;
import actoj.core.MarkerList.MarkerChangeListener;
import actoj.core.TimeInterval;
import actoj.core.TimeInterval.Units;
import actoj.fitting.FitSine;
import actoj.periodogram.EnrightPeriodogram;
import actoj.periodogram.FourierPeriodogram;
import actoj.periodogram.LombScarglePeriodogram;
import actoj.periodogram.Periodogram;
import actoj.util.Filters;
import actoj.util.PeakFinder;

/**
 * A JComponent representing one actogram plus accompanying data, like
 * calibration. It uses a ActogramProcessor to draw the actogram itself.
 */
@SuppressWarnings("serial")
public class ActogramCanvas extends JPanel
			implements MouseMotionListener, MouseListener, MarkerChangeListener {

	public static enum Mode {
		POINTING, FREERUNNING_PERIOD, SELECTING;
	}

	/** The ActogramProcessor for drawing the actogram itself. */
	public final ActogramProcessor processor;

	/** The width of this component. */
	public final int width;

	/** The height of this component. */
	public final int height;

	/**
	 * The Feedback which is called when the freerunning period triangle
	 * has changed.
	 */
	private final Feedback feedback;

	/**
	 * Start point of the freerunning period triangle within the
	 * ActogramProcessor coordinate system.
	 */
	private Point fpStart = null;

	/**
	 * Current(end) point of the freerunning period triangle within the
	 * ActogramProcessor coordinate system.
	 */
	private Point fpCurr = null;

	/**
	 * Start point of the selection interval within the
	 * ActogramProcessor coordinate system.
	 */
	private Point selStart = null;

	/**
	 * Current(end) point of the selection interval within the
	 * ActogramProcessor coordinate system.
	 */
	private Point selCurr = null;

	/** The width of the y calibration bar. */
	private final int YCALIB_WIDTH;

	/** Distance between left border and the actogram. */
	private final int INT_LEFT = 5;

	/** The total left intent */
	private final int INT_LEFT_TOTAL;

	/** Distance between right border and the actogram. */
	private final int INT_RIGHT = 5;

	/** Distance between top border and the actogram. */
	private final int INT_TOP; // accomodates the title

	private final int INT_TOP_ALL;

	/** Distance between bottom border and the actogram. */
	private final int INT_BOTTOM = 5;

	/** Stroke for the freerunning period triangle */
	private final float stroke = 3f;

	/** Font for the freerunning period triangle */
	private final Font font = new Font("Helvetica", Font.BOLD, 14);

	/** Text color for the freerunning period triangle */
	private final Color text = new Color(149, 255, 139);

	/** Color for the freerunning period triangle */
	private final Color color = new Color(26, 93, 0);

	/** Background color for the canvas */
	private final Color background = Color.WHITE;// new Color(139, 142, 255);

	private int nSubdivisions = 8;

	private final Units fpUnit;

	private Mode mode = Mode.POINTING;

	private final int extVarHeight;


	public ActogramCanvas(
				Actogram actogram,
				double zoom,
				float uLimit,
				float lLimit,
				int ppl,
				int subd,
				float whRatio,
				Units fpUnit,
				Feedback f) {
		super();
		this.processor = new ActogramProcessor(actogram, zoom, uLimit, lLimit, ppl, whRatio);
		this.feedback = f;
		this.nSubdivisions = subd;
		this.fpUnit = fpUnit;
		this.extVarHeight = 2 * processor.signalHeight;

		INT_TOP = (int) (1.5 * getTitleHeight());

		int nExternals = actogram.getExternalVariables().length;
		INT_TOP_ALL = INT_TOP + (1 + nExternals) * extVarHeight;

		YCALIB_WIDTH = calculateYCalibrationWidth();
		INT_LEFT_TOTAL = INT_LEFT + YCALIB_WIDTH;

		BufferedImage ip = processor.processor;
		width = ip.getWidth() + INT_LEFT_TOTAL + INT_RIGHT;
		height = ip.getHeight() + INT_TOP_ALL + INT_BOTTOM;

		this.setPreferredSize(new Dimension(width, height));

		addMouseListener(this);
		addMouseMotionListener(this);

		setBackground(background);
	}

	public Mode getMode() {
		return mode;
	}

	public void setMode(Mode mode) {
		this.mode = mode;
	}

	public void setNSubdivisions(int n) {
		this.nSubdivisions = n;
		repaint();
	}

	public boolean hasSelection() {
		return selStart != null && selCurr != null;
	}

	// x and y are coordinates within this component
	public String getPositionString(int x, int y) {
		if(x < INT_LEFT_TOTAL || y < INT_TOP_ALL || x >= width - INT_RIGHT || y >= height - INT_BOTTOM)
			return "outside";
		int idx = processor.getIndex(x - INT_LEFT_TOTAL, y - INT_TOP_ALL);
		if(idx < 0)
			return "outside";
		Actogram a = processor.downsampled;
		return a.name + ": " + a.getTimeStringForIndex(idx) + " ("
			+ a.get(idx) + ")";
	}

	public TimeInterval getFreerunningPeriod() {
		if(fpStart == null || fpCurr == null)
			return null;
		Point st = upper(fpStart, fpCurr);
		Point cu = lower(fpStart, fpCurr);
		int dy = (cu.y - st.y) / processor.baselineDist; // in periods
		if(dy == 0)
			return null;
		int dx = cu.x - st.x;

		double i = processor.downsampled.interval.millis;
		int spp = processor.downsampled.SAMPLES_PER_PERIOD;
		return new TimeInterval(i * (dy * spp + dx) / dy);
	}

	DecimalFormat df = new DecimalFormat("#.##");
	public String getPeriodString() {
		TimeInterval tv = getFreerunningPeriod();
		return tv == null ? "" : df.format(
			tv.intervalIn(fpUnit)) + fpUnit.abbr;
	}

	public void calculateAcrophase() {
		Actogram a = processor.original;
		calculateAcrophase(new TimeInterval(a.SAMPLES_PER_PERIOD * a.interval.millis));
	}

	public void calculateAcrophase(TimeInterval T) {
		if(selStart == null || selCurr == null)
			throw new RuntimeException("Interval required");

		Point st = upper(selStart, selCurr);
		Point cu = lower(selStart, selCurr);

		int from = processor.getIndex(st.x, st.y);
		int to = processor.getIndex(cu.x, cu.y);

		Actogram a = processor.original;
		from = processor.getIndexInOriginal(from);
		to = processor.getIndexInOriginal(to);

		ArrayList<Integer> m = Acrophase.calculate(a, from, to, T);
		MarkerList ml = new MarkerList("Acrophase", m, a.interval.millis, Color.BLUE);
		ml.addMarkerChangeListener(this);
		ml.calculateRegression(T);
		a.addMarker(ml);
		repaint();
	}

	public void calculateOnAndOffsets(float gaussianSigma, OnOffset.ThresholdMethod thresholdMethod) {
		if(selStart == null || selCurr == null)
			throw new RuntimeException("Interval required");

		Point st = upper(selStart, selCurr);
		Point cu = lower(selStart, selCurr);

		int from = processor.getIndex(st.x, st.y);
		int to = processor.getIndex(cu.x, cu.y);

		Actogram org = processor.original;
		Actogram a = org;
		if(gaussianSigma > 0) {
			float[] kernel = Filters.makeGaussianKernel(gaussianSigma);
			a = org.convolve(kernel);
		}

		from = processor.getIndexInOriginal(from);
		to = processor.getIndexInOriginal(to);
		TimeInterval period = new TimeInterval(a.SAMPLES_PER_PERIOD * a.interval.millis);

		OnOffset onoff = new OnOffset();
		onoff.calculate(a, from, to, period, thresholdMethod);
		ArrayList<Integer> on = onoff.getOnsets();
		MarkerList onML = new MarkerList("Onset", on, a.interval.millis, Color.RED);
		onML.calculateRegression(period);
		onML.addMarkerChangeListener(this);
		org.addMarker(onML);
		ArrayList<Integer> off = onoff.getOffsets();
		MarkerList offML = new MarkerList("Offset", off, a.interval.millis, Color.RED);
		offML.calculateRegression(period);
		offML.addMarkerChangeListener(this);
		org.addMarker(offML);

		repaint();
	}

	public void calculateOnAndOffsets(float gaussianSigma, float threshold) {
		if(selStart == null || selCurr == null)
			throw new RuntimeException("Interval required");

		Point st = upper(selStart, selCurr);
		Point cu = lower(selStart, selCurr);

		int from = processor.getIndex(st.x, st.y);
		int to = processor.getIndex(cu.x, cu.y);

		Actogram org = processor.original;
		Actogram a = org;
		if(gaussianSigma > 0) {
			float[] kernel = Filters.makeGaussianKernel(gaussianSigma);
			a = org.convolve(kernel);
		}

		from = processor.getIndexInOriginal(from);
		to = processor.getIndexInOriginal(to);
		TimeInterval period = new TimeInterval(a.SAMPLES_PER_PERIOD * a.interval.millis);

		OnOffset onoff = new OnOffset();
		onoff.calculate(a, from, to, period, threshold);
		ArrayList<Integer> on = onoff.getOnsets();
		MarkerList onML = new MarkerList("Onset", on, a.interval.millis, Color.RED);
		onML.calculateRegression(period);
		onML.addMarkerChangeListener(this);
		org.addMarker(onML);
		ArrayList<Integer> off = onoff.getOffsets();
		MarkerList offML = new MarkerList("Offset", off, a.interval.millis, Color.RED);
		offML.calculateRegression(period);
		offML.addMarkerChangeListener(this);
		org.addMarker(offML);

		repaint();
	}

	public void calculateAverageActivity(TimeInterval period, float sigma) {
		if(selStart == null || selCurr == null)
			throw new RuntimeException("Interval required");

		Point st = upper(selStart, selCurr);
		Point cu = lower(selStart, selCurr);
		if(st.y == cu.y && st.x > cu.x) {
			Point tmp = st; st = cu; cu = tmp;
		}
		int sIdx = processor.getIndex(st.x, st.y);
		if(sIdx < 0) return;
		int cIdx = processor.getIndex(cu.x, cu.y);
		if(cIdx < 0) return;

		sIdx = processor.getIndexInOriginal(sIdx);
		cIdx = processor.getIndexInOriginal(cIdx);

		Actogram acto = processor.original;
		if(sigma > 0) {
			float[] kernel = Filters.makeGaussianKernel(sigma);
			acto = acto.convolve(kernel);
		}

		int periodIdx = acto.getIndexForTime(period);

		float[] values = AverageActivity.calculateAverageActivity(
				acto, sIdx, cIdx, periodIdx);

		String unit = acto.unit.abbr;
		float[] time = new float[periodIdx];
		float factor = acto.interval.intervalIn(acto.unit);
		for(int i = 0; i < periodIdx; i++)
			time[i] = i * factor;

		double[] yminmax = Tools.getMinMax(values);
		double[] xminmax = Tools.getMinMax(time);
		/* pad a bit */
		yminmax[0] -= 0.1 * (yminmax[1] - yminmax[0]);

		Plot plot = new Plot(
			"Average Activity Pattern - " + acto.name,
			"Time (" + unit + ")",
			"Average activity",
			time,
			values,
			Plot.LINE);
		int W = 450 + Plot.LEFT_MARGIN + Plot.RIGHT_MARGIN;
		int H = 200 + Plot.TOP_MARGIN + Plot.BOTTOM_MARGIN;
		plot.setSize(W, H);
		plot.setLimits(xminmax[0], xminmax[1], yminmax[0], yminmax[1]);

		plot.setColor(Color.BLUE);
		plot.draw();
		plot.show();
	}

	/**
	 * @param method: 0 - Fourier, 1 - Enright, 2 - Lomb-Scargle
	 */
	public void calculatePeriodogram(TimeInterval fromPeriod, TimeInterval toPeriod,
			int method, int nPeaks,
			float sigma, int stepsize, double pLevel) {

		if(selStart == null || selCurr == null)
			throw new RuntimeException("Interval required");

		Point st = upper(selStart, selCurr);
		Point cu = lower(selStart, selCurr);
		if(st.y == cu.y && st.x > cu.x) {
			Point tmp = st; st = cu; cu = tmp;
		}
		int sIdx = processor.getIndex(st.x, st.y);
		if(sIdx < 0) return;
		int cIdx = processor.getIndex(cu.x, cu.y);
		if(cIdx < 0) return;

		sIdx = processor.getIndexInOriginal(sIdx);
		cIdx = processor.getIndexInOriginal(cIdx);

		Actogram org = processor.original;
		Actogram acto = org;
		if(sigma > 0) {
			float[] kernel = Filters.makeGaussianKernel(sigma);
			acto = org.convolve(kernel);
		}

		if(stepsize > 1) {
			try {
				acto = acto.downsample(stepsize);
				double zoom = org.SAMPLES_PER_PERIOD / (double)acto.SAMPLES_PER_PERIOD;
				sIdx = (int)Math.round(sIdx / zoom);
				cIdx = (int)Math.round(cIdx / zoom);
			} catch(Exception e) {
				IJ.error("Invalid downsampling factor");
				return;
			}
		}

		int fromPeriodIdx = acto.getIndexForTime(fromPeriod);
		int toPeriodIdx = acto.getIndexForTime(toPeriod);

		Periodogram fp = null;
		switch(method) {
			case 0:
				fp = new FourierPeriodogram(acto, sIdx,
					cIdx, fromPeriodIdx, toPeriodIdx, pLevel);
				break;
			case 1:
				fp = new EnrightPeriodogram(acto, sIdx,
					cIdx, fromPeriodIdx, toPeriodIdx, pLevel);
				break;
			case 2:
				fp = new LombScarglePeriodogram(acto, sIdx,
					cIdx, fromPeriodIdx, toPeriodIdx, pLevel);
				break;
			default: throw new RuntimeException(
					   "Invalid periodogram method");
		}

		String unit = acto.unit.abbr;
		float[] values = fp.getPeriodogramValues();
		float[] pValues = fp.getPValues();
		float[] periods = fp.getPeriod();

		/* incorporate calibration */
		float factor = acto.interval.intervalIn(acto.unit);
		for(int i = 0; i < periods.length; i++)
			periods[i] *= factor;

		/* use pvalues as references */
		float[] relatives = new float[values.length];
		System.arraycopy(values, 0, relatives, 0, values.length);
		if(fp.canCalculatePValues()) {
			for(int i = 0; i < values.length; i++)
				relatives[i] -= pValues[i];
		}

		/* find peaks */
		int[] peaks = PeakFinder.findPeaks(relatives);

		double[] yminmax = Tools.getMinMax(values);
		double[] xminmax = Tools.getMinMax(periods);
		/* pad a bit */
		yminmax[0] -= 0.1 * (yminmax[1] - yminmax[0]);

		Plot plot = new Plot(
			"Periodogram (" + fp.getMethod() + ") - " + acto.name,
			"Period (" + unit + ")",
			fp.getResponseName(),
			periods,
			values,
			Plot.LINE);
		int W = 450 + Plot.LEFT_MARGIN + Plot.RIGHT_MARGIN;
		int H = 200 + Plot.TOP_MARGIN + Plot.BOTTOM_MARGIN;
		plot.setSize(W, H);
		plot.setLimits(xminmax[0], xminmax[1], yminmax[0], yminmax[1]);

		plot.setColor(Color.BLUE);
		plot.draw();
		if(fp.canCalculatePValues()) {
			plot.setColor(Color.RED);
			plot.addPoints(periods, pValues, Plot.LINE);
		}
		plot.draw();
		plot.setColor(Color.BLACK);

		/* draw the peaks */
		for(int i = 0; i < nPeaks && i < peaks.length; i++) {
			int p = peaks[i];
			plot.drawLine(
				(p + fromPeriodIdx) * factor,
				yminmax[0],
				(p + fromPeriodIdx) * factor,
				values[p]);

			float x = p / (float)(toPeriodIdx - fromPeriodIdx);
			float y = (float)((yminmax[1] - values[p]) / (yminmax[1] - yminmax[0]));
			float period = (fromPeriodIdx + p) * factor;
			plot.addLabel(x, y, df.format(period));
		}
		plot.show();
	}

	public void fitSine() {
		if(selStart == null || selCurr == null)
			throw new RuntimeException("Interval required");

		Point st = upper(selStart, selCurr);
		Point cu = lower(selStart, selCurr);
		if(st.y == cu.y && st.x > cu.x) {
			Point tmp = st; st = cu; cu = tmp;
		}
		int sIdx = processor.getIndex(st.x, st.y);
		if(sIdx < 0) return;
		int cIdx = processor.getIndex(cu.x, cu.y);
		if(cIdx < 0) return;

		sIdx = processor.getIndexInOriginal(sIdx);
		cIdx = processor.getIndexInOriginal(cIdx);

		Actogram org = processor.original;

		double[] param = FitSine.fit(org, sIdx, cIdx);
		Actogram pred = FitSine.getCurve(org, param);

		pred = pred.downsample(processor.zoom);
		processor.drawInto(pred,
			new ActogramProcessor.Histogram(
				new GraphicsBackend(processor.processor.createGraphics())),
			new Color(1f, 0f, 0f, 0.5f));

		selStart = null;
		selCurr = null;
		repaint();

		StringBuffer msg = new StringBuffer();
		msg.append("Start:    ").append(org.getTimeStringForIndex(sIdx));
		msg.append("\n");
		msg.append("End:      ").append(org.getTimeStringForIndex(cIdx));
		msg.append("\n \n");
		msg.append("Function: min(0, a * sin(b * t + c) + d)\n \n");
		msg.append("a = ").append((float)param[0]).append("\n");
		double w = param[1] / org.interval.millis;
		msg.append("b = ").append((float)w).append("/ms").append("\n");
		msg.append("c = ").append((float)param[2]).append("\n");
		msg.append("d = ").append((float)param[3]).append("\n \n");
		double T = 2 * Math.PI / w; // in ms
		msg.append("T = " + new TimeInterval(Math.round(T)).
						intervalIn(org.unit));

		IJ.showMessage(msg.toString());
	}

	@Override
	public void paintComponent(Graphics g) {
		Graphics2D g2d = (Graphics2D)g;
		g2d.setRenderingHint(
			RenderingHints.KEY_ANTIALIASING,
			RenderingHints.VALUE_ANTIALIAS_ON);

		GraphicsBackend gb = new GraphicsBackend(g);

		clearBackground(gb);
		drawTitle(gb);
		drawXCalibration(gb);

		ExternalVariable[] ev = processor.original.getExternalVariables();
		for(int i = 0; i < ev.length; i++) {
			gb.setOffsX(INT_LEFT_TOTAL);
			gb.setOffsY(INT_TOP + (i + 1) * extVarHeight);
			ev[i].paint(gb, processor.width, extVarHeight, processor.ppl);
		}

		gb.setOffsX(0);
		gb.setOffsY(0);

		g.drawImage(processor.processor, INT_LEFT_TOTAL, INT_TOP_ALL, null);

		drawYCalibration(gb);

		for(int m = 0; m < processor.original.nMarkers(); m++)
			drawMarkers(gb, processor.original.getMarker(m));

		drawSelection(gb);
		drawFPTriangle(gb);
	}

	public void paint(DrawingBackend gb) {
		clearBackground(gb);
		drawTitle(gb);
		drawXCalibration(gb);

		float offX = gb.getOffsX(), offY = gb.getOffsY();

		ExternalVariable[] ev = processor.original.getExternalVariables();
		for(int i = 0; i < ev.length; i++) {
			gb.setOffsX(offX + gb.getFactorX() * INT_LEFT_TOTAL);
			gb.setOffsY(offY + gb.getFactorY() * (INT_TOP + (i + 1) * extVarHeight));
			ev[i].paint(gb, processor.width, extVarHeight, processor.ppl);
		}

		gb.setOffsX(offX + gb.getFactorX() * INT_LEFT_TOTAL);
		gb.setOffsY(offY + gb.getFactorY() * INT_TOP_ALL);
		processor.clearBackground(gb);
		processor.drawInto(processor.downsampled,
				new ActogramProcessor.Histogram(gb), Color.BLACK);
		gb.setOffsX(offX);
		gb.setOffsY(offY);

		drawYCalibration(gb);

		for(int m = 0; m < processor.original.nMarkers(); m++)
			drawMarkers(gb, processor.original.getMarker(m));

		drawSelection(gb);
		drawFPTriangle(gb);
	}

	private void clearBackground(DrawingBackend g) {
		g.setFillColor(background.getRGB());
		g.moveTo(0, 0);
		g.fillRectangle(width, height);
		g.setLineColor(Color.BLACK.getRGB());
		g.drawRectangle(width, height);
	}

	private void drawSelection(DrawingBackend g) {
		if(selStart == null || selCurr == null)
			return;

		Point st = upper(selStart, selCurr);
		Point cu = lower(selStart, selCurr);
		if(st.y == cu.y && st.x > cu.x) {
			Point tmp = st; st = cu; cu = tmp;
		}

		Point s = new Point(st.x + INT_LEFT_TOTAL, st.y + INT_TOP_ALL);
		Point c = new Point(cu.x + INT_LEFT_TOTAL, cu.y + INT_TOP_ALL);

		int x0 = INT_LEFT_TOTAL;
		int x1 = INT_LEFT_TOTAL + processor.processor.getWidth();
		int sh = processor.signalHeight;

		// draw start marker
		g.setFillColor(255, 0, 0, 255);
		g.moveTo(s.x - 1, s.y - sh);
		g.fillRectangle(2, sh);

		// draw end marker
		g.moveTo(c.x - 1, c.y - sh);
		g.fillRectangle(2, sh);

		g.setFillColor(255, 0, 0, 150);

		// on the same line
		if(s.y == c.y) {
			g.moveTo(s.x, s.y - sh);
			g.fillRectangle(c.x - s.x, sh);
			return;
		}

		// on different lines
		g.moveTo(s.x, s.y - sh);
		g.fillRectangle(x1 - s.x, sh);
		g.moveTo(x0, c.y - sh);
		g.fillRectangle(c.x - x0, sh);
		int cury = s.y + processor.baselineDist;
		while(cury < c.y) {
			g.moveTo(x0, cury - sh);
			g.fillRectangle(x1 - x0, sh);
			cury += processor.baselineDist;
		}
	}

	private void drawFPTriangle(DrawingBackend g) {
		if(fpStart == null || fpCurr == null)
			return;

		Point st = upper(fpStart, fpCurr);
		Point cu = lower(fpStart, fpCurr);
		int sIdx = processor.getIndex(st.x, st.y);
		if(sIdx < 0) return;
		int cIdx = processor.getIndex(cu.x, cu.y);
		if(cIdx < 0) return;

		Point s = new Point(st.x + INT_LEFT_TOTAL, st.y + INT_TOP_ALL);
		Point c = new Point(cu.x + INT_LEFT_TOTAL, cu.y + INT_TOP_ALL);

		g.setLineColor(color.getRGB());
		g.setLineWidth(stroke);
		g.setLineDashPattern(new float[] {10, 0});
		g.moveTo(c.x, c.y);
		g.lineTo(s.x, s.y);
		g.lineTo(s.x, c.y);
		g.lineTo(c.x, c.y);
		int dy = (cu.y - st.y) / processor.baselineDist; // in periods
		double dx = Math.abs(cu.x - st.x);
		dx *= processor.downsampled.interval.millis;
		String v = dy + " cycles";
		String h = new TimeInterval(dx).toString();
		g.setFont(font);
		FontMetrics fm = getFontMetrics(font);
		int fh = fm.getHeight();

		// v string
		Rectangle vr = new Rectangle(
			s.x - fm.stringWidth(v) - 2,
			(s.y + c.y + fh) / 2,
			fm.stringWidth(v), fh);
		g.setFillColor(color.getRGB());
		g.moveTo(vr.x, vr.y - fh + 2);
		g.fillRectangle(vr.width, vr.height);
		g.setLineColor(text.getRGB());
		g.setFillColor(text.getRGB());
		g.moveTo(vr.x, vr.y);
		g.drawText(v);

		// h string
		Rectangle hr = new Rectangle(
			(s.x + c.x - fm.stringWidth(h)) / 2,
			c.y + fh + 2,
			fm.stringWidth(h), fh);
		g.setFillColor(color.getRGB());
		g.moveTo(hr.x, hr.y - fh + 2);
		g.fillRectangle(hr.width, hr.height);
		g.setLineColor(text.getRGB());
		g.setFillColor(text.getRGB());
		g.moveTo(hr.x, hr.y);
		g.drawText(h);
	}

	private void drawMarkers(DrawingBackend g, MarkerList markers) {
		g.setFillColor(markers.getColor().getRGB());
		g.setLineColor(markers.getColor().getRGB());
		int radius = processor.signalHeight / 4;
		double calibration = markers.getCalibration();

		Point[] points = new Point[processor.ppl];
		for(int i = 0; i < points.length; i++)
			points[i] = new Point();

		int indexInPlotPerLine = markers.getIndexInPlotPerLine();
		for(int position : markers) {
			double realpos = position * calibration;
			int index = processor.downsampled.getIndexForTime(new TimeInterval(realpos));

			processor.getPoint(index, points);

			Point p = points[indexInPlotPerLine];
			p.x += INT_LEFT_TOTAL;
			p.y += INT_TOP_ALL;

			float x0, x1, x2, y0, y1, y2;
			x0 = p.x - radius;
			x1 = p.x;
			x2 = p.x + radius;
			y0 = p.y + radius;
			y1 = p.y;
			y2 = p.y + radius;
			g.fillTriangle(x0, y0, x1, y1, x2, y2);
		}

		MarkerList.RegressionLine regression = markers.getRegression();
		if(regression == null)
			return;

		// calculate for first line:
		processor.getPoint(0, points);
		g.clip(points[indexInPlotPerLine].x + INT_LEFT_TOTAL, INT_TOP_ALL, processor.width / processor.ppl, processor.height);

		double v = regression.t;
		int i = processor.downsampled.getIndexForTime(new TimeInterval(v));
		int shift = 0;
		while(i < 0) {
			i += processor.downsampled.SAMPLES_PER_PERIOD;
			shift++;
		}
		while(i >= processor.downsampled.SAMPLES_PER_PERIOD) {
			i -= processor.downsampled.SAMPLES_PER_PERIOD;
			shift--;
		}

		g.setLineWidth(markers.getLinewidth());
		for(int l = 1; l < processor.periods; l++) {
			v = regression.t + regression.m * l;
			i = processor.downsampled.getIndexForTime(new TimeInterval(v));
			int shift1 = 0;
			while(i < 0) {
				i += processor.downsampled.SAMPLES_PER_PERIOD;
				shift1++;
			}
			while(i >= processor.downsampled.SAMPLES_PER_PERIOD) {
				i -= processor.downsampled.SAMPLES_PER_PERIOD;
				shift1--;
			}

			if(shift1 != shift || l == processor.periods - 1) { // i.e. these we can now draw the lines for the prev. shift
				int perl = -20;
				int peru = processor.periods - 1 + 20;
				double v0 = regression.t + regression.m * regression.firstPeriod;
				double v1 = regression.t + regression.m * regression.lastPeriod;
				double vl = regression.t + regression.m * perl;
				double vu = regression.t + regression.m * peru;

				// indices within period:
				int i0 = shift * processor.downsampled.SAMPLES_PER_PERIOD + processor.downsampled.getIndexForTime(new TimeInterval(v0));
				int i1 = shift * processor.downsampled.SAMPLES_PER_PERIOD + processor.downsampled.getIndexForTime(new TimeInterval(v1));
				int il = shift * processor.downsampled.SAMPLES_PER_PERIOD + processor.downsampled.getIndexForTime(new TimeInterval(vl));
				int iu = shift * processor.downsampled.SAMPLES_PER_PERIOD + processor.downsampled.getIndexForTime(new TimeInterval(vu));

				Point p0 = processor.getPoint(regression.firstPeriod, i0, indexInPlotPerLine);
				p0.x += INT_LEFT_TOTAL;
				p0.y += INT_TOP_ALL;

				Point p1 = processor.getPoint(regression.lastPeriod, i1, indexInPlotPerLine);
				p1.x += INT_LEFT_TOTAL;
				p1.y += INT_TOP_ALL;

				Point pl = processor.getPoint(perl, il, indexInPlotPerLine);
				pl.x += INT_LEFT_TOTAL;
				pl.y += INT_TOP_ALL;

				Point pu = processor.getPoint(peru, iu, indexInPlotPerLine);
				pu.x += INT_LEFT_TOTAL;
				pu.y += INT_TOP_ALL;

				float lw = markers.getLinewidth();
				g.moveTo(pl.x, pl.y);
				g.setLineDashPattern(new float[] {lw, 4 * lw});
				g.lineTo(p0.x, p0.y);
				g.setLineDashPattern(new float[] {10, 0});
				g.lineTo(p1.x, p1.y);
				g.setLineDashPattern(new float[] {lw, 4 * lw});
				g.lineTo(pu.x, pu.y);
			}
			shift = shift1;
		}
		g.resetClip();
	}

	private int getTitleHeight() {
		int fs = (int)(0.6 * processor.baselineDist);
		Font font = new Font("Helvetica", Font.BOLD, fs);
		FontMetrics fm = getFontMetrics(font);
		return fm.getHeight();
	}

	private void drawTitle(DrawingBackend g) {
		int fs = (int)(0.6 * processor.baselineDist);
		String title = processor.original.name + ":";
		Font font = new Font("Helvetica", Font.BOLD, fs);
		g.setFont(font);
		FontMetrics fm = getFontMetrics(font);
		int h = fm.getHeight();
		int w = fm.stringWidth(title);
		int x = width / 2 - w / 2;
		int y = INT_TOP / 2 + h / 2;
		g.setLineColor(0, 0, 0, 255);
		g.setLineWidth(1);
		g.moveTo(x, y - h);
		g.setFillColor(150, 150, 150, 255);
		g.fillRectangle(w + 1, h + 1);
		g.moveTo(x, y + 1);
		g.lineTo(x + w, y + 1);
		g.moveTo(x, y);
		g.setFillColor(0, 0, 0, 255);
		g.setLineColor(0, 0, 0, 255);
		g.drawText(title);
	}

	private void drawXCalibration(DrawingBackend g) {
		int left = INT_LEFT_TOTAL;
		int w = width - left - INT_RIGHT;
		int h = extVarHeight;

		g.setLineColor(0, 0, 0, 255);
		g.setLineWidth(1f);
		g.moveTo(left, INT_TOP + h/2);
		g.lineTo(width - INT_RIGHT, INT_TOP + h/2);
		for(int i = 0; i <= nSubdivisions; i++) {
			int x = left + Math.round((float)i * w / nSubdivisions);
			g.moveTo(x, INT_TOP + h/2 - h/4);
			g.lineTo(x, INT_TOP + h/2 + h/4);
		}
	}

	private int calculateYCalibrationWidth() {
		int fs = Math.min((int)(0.6 * processor.baselineDist), 12);
		Font font = new Font("Helvetica", Font.PLAIN, fs);
		int nLines = processor.periods + 1;
		FontMetrics fm = getFontMetrics(font);
		int max = 0;
		for(int i = 0; i < nLines - 1; i++) {
			int w = fm.stringWidth(Integer.toString(i + 1));
			if(w > max)
				max = w;
		}
		return max + 5;
	}

	private void drawYCalibration(DrawingBackend g) {
		int fs = Math.min((int)(0.6 * processor.baselineDist), 12);
		Font font = new Font("Helvetica", Font.PLAIN, fs);
		g.setFont(font);
		int nLines = processor.periods + 1;
		String[] numbers = new String[nLines];
		int[] widths = new int[nLines];
		FontMetrics fm = getFontMetrics(font);
		for(int i = 0; i < nLines - 1; i++) {
			numbers[i] = Integer.toString(i + 1);
			widths[i] = fm.stringWidth(numbers[i]);
		}

		g.setLineColor(0, 0, 0, 255);
		g.setLineWidth(1f);

		int x = INT_LEFT_TOTAL - widths[0] - 5;
		int y = INT_TOP_ALL + processor.baselineDist + processor.baselineDist;
		g.moveTo(x, y);
		g.drawText(numbers[0]);
		for(int i = 4; i < nLines - 1; i += 5) {
			x = INT_LEFT_TOTAL - widths[i] - 5;
			y = INT_TOP_ALL + (i + 1) * processor.baselineDist + processor.baselineDist;
			g.moveTo(x, y);
			g.drawText(numbers[i]);
		}
	}

	public Point snap(Point in) {
		if(in.x < INT_LEFT_TOTAL)
			in.x = INT_LEFT_TOTAL;
		if(in.y < INT_TOP_ALL)
			in.y = INT_TOP_ALL;
		if(in.x >= width - INT_RIGHT)
			in.x = width - INT_RIGHT - 1;
		if(in.y >= height - INT_BOTTOM)
			in.y = height - INT_BOTTOM - 1;
		int bd = processor.baselineDist;
		int idx = (in.y - INT_TOP_ALL)/ bd;
		return processor.clamp(in.x - INT_LEFT_TOTAL, (idx + 1) * bd);
	}

	private static Point upper(Point p1, Point p2) {
		if(p1 == null || p2 == null)
			return null;
		return p1.y <= p2.y ? p1 : p2;
	}

	private static Point lower(Point p1, Point p2) {
		if(p1 == null || p2 == null)
			return null;
		return p1.y <= p2.y ? p2 : p1;
	}

	@Override
	public void mouseEntered(MouseEvent e) {}
	@Override
	public void mouseExited(MouseEvent e) {}
	@Override
	public void mouseReleased(MouseEvent e) {}

	@Override
	public void mouseClicked(MouseEvent e) {
		if(mode == Mode.FREERUNNING_PERIOD) {
			fpStart = null;
			fpCurr = null;
			if(feedback != null)
				feedback.periodChanged(getPeriodString());
			repaint();
		} else if(mode == Mode.SELECTING) {
			selStart = null;
			selCurr = null;
			repaint();
		}
	}

	@Override
	public void mousePressed(MouseEvent e) {
		if(mode == Mode.FREERUNNING_PERIOD) {
			fpStart = snap(e.getPoint());
			fpCurr = snap(e.getPoint());
			repaint();
		} else if(mode == Mode.SELECTING) {
			selStart = snap(e.getPoint());
			selCurr = snap(e.getPoint());
			repaint();
		}
	}

	@Override
	public void mouseMoved(MouseEvent e) {
		if(feedback != null)
			feedback.positionChanged(
				getPositionString(e.getX(), e.getY()));
	}

	@Override
	public void mouseDragged(MouseEvent e) {
		if(feedback != null)
			feedback.positionChanged(
				getPositionString(e.getX(), e.getY()));

		if(mode == Mode.FREERUNNING_PERIOD) {
			Point tmp = snap(e.getPoint());

			fpCurr = tmp;
			if(feedback != null)
				feedback.periodChanged(getPeriodString());
			repaint();
		} else if(mode == Mode.SELECTING) {
			selCurr = snap(e.getPoint());
			repaint();
		}
	}

	@Override
	public void markerChanged(MarkerList ml) {
		repaint();
	}

	public static interface Feedback {
		public void positionChanged(String pos);
		public void periodChanged(String per);
	}
}

