package actoj.gui;

import java.awt.Color;
import java.awt.Point;
import java.awt.image.BufferedImage;

import actoj.core.Actogram;

public class ActogramProcessor {

	public final Actogram original;
	public final Actogram downsampled;
	public final BufferedImage processor;

	public final double zoom;
	public final float uLimit;
	public final float lLimit;
	public final int ppl;

	public final int baselineDist;
	public final int signalHeight;
	public final int periods;

	public final int width;
	public final int height;

	public final float whRatio;

	public ActogramProcessor(Actogram actogram, double zoom, float uLimit, float lLimit, int ppl, float whRatio) {
		this.original = actogram;

		int newSPP = (int)Math.round(actogram.SAMPLES_PER_PERIOD / zoom);
		this.zoom = actogram.SAMPLES_PER_PERIOD / (double)newSPP;
		this.downsampled = actogram.downsample(this.zoom);

		this.uLimit = uLimit;
		this.lLimit = lLimit;
		this.ppl = ppl;
		this.whRatio = whRatio;

		this.periods = (int)Math.ceil(downsampled.size() /
			(float)downsampled.SAMPLES_PER_PERIOD);
		int spp = downsampled.SAMPLES_PER_PERIOD;

		int nlines = periods + 1;

		this.baselineDist = (int)Math.ceil(ppl * spp / (whRatio * nlines));
		this.signalHeight = (int)Math.ceil(baselineDist * 0.75);

		this.width = ppl * spp + 2;
		this.height = nlines * baselineDist;

		this.processor = createProcessor();
		drawInto(downsampled, new Histogram(
			new GraphicsBackend(processor.getGraphics())), new Color(50, 50, 50));
	}

	/**
	 * Transforms the given index in the downsampled
	 * actogram to the corresponding index in the original
	 * actogram.
	 */
	public int getIndexInOriginal(int i) {
		return (int)Math.floor(i * zoom);
	}

	/**
	 * Transforms the given index in the original
	 * actogram to the corresponding index in the downsampled
	 * actogram.
	 */
	public int getIndexInDownsampled(int i) {
		return (int)Math.round(i / zoom);
	}

	public int getIndex(int x, int y) {
		if(x < 1 || x >= width - 1)
			return -1;
		x -= 1; // there's a 1px border
		int spp = downsampled.SAMPLES_PER_PERIOD;
		int lineIdx = (y - 1) / baselineDist;
		int colIdx = x / spp;

		int period = lineIdx - 1 + colIdx;
		if(period < 0 || period >= periods)
			return -1;

		int index = period * spp + x % spp;
		if(index < 0 || index >= downsampled.size())
			return -1;

		return index;
	}

	public Point clamp(int x, int y) {
		if(x < 1)
			x = 1;
		if(x >= width - 1)
			x = width - 2;
		if(y < 0)
			y = 0;
		if(y >= height)
			y = height - 1;

		x -= 1;

		int spp = downsampled.SAMPLES_PER_PERIOD;
		int lineIdx = (y - 1) / baselineDist;
		int colIdx = x / spp;

		int period = lineIdx - 1 + colIdx;
		if(period < 0)
			x = spp;
		int index = period * spp + x % spp;
		if(index >= downsampled.size())
			x = downsampled.size() - 1 - (periods - 1) * spp;

		return new Point(x + 1, y);
	}

	public int getLineIndex(int y) {
		return (y - 1) / baselineDist;
	}

	// points[xfold]
	public void getPoint(int index, Point[] points) {
		int spp = downsampled.SAMPLES_PER_PERIOD;
		int period = index / spp;
		int mod = index % spp;

		// one point in the first line
		// + 1 for the 1px border
		points[0].x = (ppl - 1) * spp + mod + 1;
		points[0].y = (period + 1) * baselineDist;

		// ... and all other points in the next line
		for(int i = 1; i < ppl; i++) {
			points[i].x = (i - 1) * spp + mod + 1;
			points[i].y = (period + 2) * baselineDist;
		}
	}

	public Point getPoint(int period, int iWithinPeriod, int indexInPlotPerLine) {
		int spp = downsampled.SAMPLES_PER_PERIOD;
		Point p = new Point();

		if(indexInPlotPerLine == 0) {
			p.x = (ppl - 1) * spp + iWithinPeriod + 1;
			p.y = (period + 1) * baselineDist;
		} else {
			p.x = (indexInPlotPerLine - 1) * spp + iWithinPeriod + 1;
			p.y = (period + 2) * baselineDist;
		}
		return p;
	}

	private BufferedImage createProcessor() {
		BufferedImage bImage = new BufferedImage(width, height,
			BufferedImage.TYPE_INT_ARGB);

		clearBackground(new GraphicsBackend(bImage.getGraphics()));
		return bImage;
	}

	public void clearBackground(DrawingBackend ba) {
		ba.moveTo(0, 0);

		ba.setFillColor(255, 255, 255, 255);
		ba.fillRectangle(width, height);

//		int evenColor = Color.WHITE.getRGB();
//		int oddColor  = Color.LIGHT_GRAY.getRGB();
//
//		for(int i = 0; i < ppl; i++) {
//			ba.setFillColor(i % 2 == 0 ? evenColor : oddColor);
//			ba.moveTo(i * width / ppl, 0);
//			ba.fillRectangle(width / ppl, height);
//		}
//
//		ba.moveTo(0, 0);
		ba.setLineColor(50, 50, 50, 255);
		ba.drawRectangle(width, height);
	}

	public void drawInto(Actogram actogram, Style style, Color color) {
		int spp = actogram.SAMPLES_PER_PERIOD;
		int nlines = periods + 1;

		for(int l = 0; l < nlines; l++) {
			for(int c = 0; c < ppl; c++) {
				int d = l - 1 + c;
				if(d < 0 || d >= periods)
					continue;
				int y = (l + 1) * baselineDist;
				int x = c * spp + 1;
				drawPeriod(actogram, d, style, color, x, y);
			}
		}
	}

	private void drawPeriod(Actogram actogram, int d, Style style, Color color, int x, int y) {
		DrawingBackend g = style.getBackend();
		int spp = actogram.SAMPLES_PER_PERIOD;

		int offs = spp * d;
		int length = offs + spp < actogram.size() ?
				spp : actogram.size() - offs;
		// draw baseline
		g.setLineColor(0, 0, 0, 255);
		g.moveTo(x, y);
		g.lineTo(x + length - 1, y);
		// draw signal
		g.setFillColor(color.getRGB());
		style.newline(x, y);
		for(int i = offs; i < offs + length; i++, x++) {
			float v = actogram.get(i);
			// Clamp it to [lLimit, uLimit]
			if(v > uLimit)
				v = uLimit;
			if(v < lLimit)
				v = lLimit;
			// normalize it to [lLimit, uLimit]
			v = (v - lLimit) / (uLimit - lLimit);
			int sh = Math.round(signalHeight * v);
			style.newData(sh);
		}
	}

	public static interface Style {
		public void newline(int x, int y);
		public void newData(int d);
		public DrawingBackend getBackend();
	}

	public static class Histogram implements Style {

		int x, y;
		final DrawingBackend ba;

		public Histogram(DrawingBackend ba) {
			this.ba = ba;
		}

		@Override
		public void newline(int x, int y) {
			this.x = x;
			this.y = y;
		}

		@Override
		public void newData(int d) {
			ba.moveTo(x, y - d);
			ba.fillRectangle(1, d);
			this.x++;
		}

		@Override
		public DrawingBackend getBackend() {
			return ba;
		}
	}

	public static class Lines implements Style {

		int last = -1;

		int x, y;
		final DrawingBackend ba;

		public Lines(DrawingBackend ba) {
			this.ba = ba;
		}

		@Override
		public void newline(int x, int y) {
			this.x = x;
			this.y = y;
			last = -1;
		}

		@Override
		public void newData(int d) {
			if(last == -1)
				last = d;
			ba.moveTo(x - 1, y - last);
			ba.lineTo(x, y - d);
			last = d;
			this.x++;
		}

		@Override
		public DrawingBackend getBackend() {
			return ba;
		}
	}
}

