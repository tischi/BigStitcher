package algorithm;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import algorithm.globalopt.GlobalOptimizationParameters;
import algorithm.globalopt.GlobalTileOptimization;
import algorithm.globalopt.PairwiseStitchingResult;
import input.FractalSpimDataGenerator;
import mpicbg.models.TranslationModel3D;
import net.imglib2.FinalInterval;
import net.imglib2.Interval;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.RealInterval;
import net.imglib2.RealLocalizable;
import net.imglib2.algorithm.phasecorrelation.PhaseCorrelation2;
import net.imglib2.algorithm.phasecorrelation.PhaseCorrelationPeak2;
import net.imglib2.img.array.ArrayImgFactory;
import net.imglib2.realtransform.AbstractTranslation;
import net.imglib2.realtransform.AffineTransform3D;
import net.imglib2.type.numeric.RealType;
import net.imglib2.type.numeric.complex.ComplexFloatType;
import net.imglib2.type.numeric.integer.LongType;
import net.imglib2.type.numeric.real.FloatType;
import net.imglib2.util.Pair;
import net.imglib2.util.Util;
import net.imglib2.util.ValuePair;
import net.imglib2.view.Views;

public class PairwiseStitching {

	/**
	 * The absolute shift of input2 relative to after PCM input1 (without t1 and t2 - they just help to speed it up)
	 * 
	 * @param img1 - zero-min interval, starting at (0,0,...)
	 * @param img2 - zero-min interval, starting at (0,0,...)
	 * @param t1
	 * @param t2
	 * @param subpixelShift
	 * @param minOverlap
	 * @param service
	 * @return
	 */
	public static <T extends RealType<T>, S extends RealType<S>> Pair< double[], Double > getShift(
			final RandomAccessibleInterval<T> input1,
			final RandomAccessibleInterval<S> input2,
			final AbstractTranslation t1,
			final AbstractTranslation t2,
			final PairwiseStitchingParameters params,
			final ExecutorService service )
	{
		final RandomAccessibleInterval<T> img1;
		final RandomAccessibleInterval<S> img2;
		
		// make sure it is zero-min
		if ( !Views.isZeroMin( input1 ) )
			img1 = Views.zeroMin( input1 );
		else
			img1 = input1;

		if ( !Views.isZeroMin( input2 ) )
			img2 = Views.zeroMin( input2 );
		else
			img2 = input2;
		
		//ImageJFunctions.show( img1 );
		//ImageJFunctions.show( img2 );
		
		final RealInterval transformed1 = TransformTools.applyTranslation( img1, t1 );
		final RealInterval transformed2 = TransformTools.applyTranslation( img2, t2 );

		System.out.println( "1: " + Util.printInterval( img1 ) );
		System.out.println( "1: " + TransformTools.printRealInterval( transformed1 ));
		System.out.println( "2: " + Util.printInterval( img2 ) );
		System.out.println( "2: " + TransformTools.printRealInterval( transformed2 ));

		final RealInterval overlap = TransformTools.getOverlap( transformed1, transformed2 );
		System.out.println( "O: " + TransformTools.printRealInterval( overlap ));

		// not overlapping
		if ( overlap == null )
			return null;

		final RealInterval localOverlap1 = TransformTools.getLocalOverlap( transformed1, overlap );
		final RealInterval localOverlap2 = TransformTools.getLocalOverlap( transformed2, overlap );

		final Interval interval1 = TransformTools.getLocalRasterOverlap( localOverlap1 );
		final Interval interval2 = TransformTools.getLocalRasterOverlap( localOverlap2 );
		
		System.out.println( "1: " + TransformTools.printRealInterval( localOverlap1 ));
		System.out.println( "1: " + Util.printInterval( interval1 ) );
		System.out.println( "2: " + TransformTools.printRealInterval( localOverlap2 ));
		System.out.println( "2: " + Util.printInterval( interval2 ) );
		

		long nPixel = 1;
		// test if the overlap is too small to begin with
		for ( int d = 0; d < img1.numDimensions(); ++d )
				nPixel += img1.dimension( d );
				
		if (nPixel < params.minOverlap)
				return null;

		//
		// call the phase correlation
		//
		final int [] extension = new int[ img1.numDimensions() ];
		Arrays.fill( extension, 10 );

		//ImageJFunctions.show( Views.zeroMin( Views.interval( img1, interval1 ) ) );
		//ImageJFunctions.show( Views.zeroMin( Views.interval( img2, interval2 ) ) );

		System.out.println( "FFT" );
		// TODO: Do not extend by mirror inside, but do that out here on the full image,
		//       so we feed it RandomAccessible + an Interval we want to use for the PCM > also zero-min inside
		final RandomAccessibleInterval<FloatType> pcm = PhaseCorrelation2.calculatePCM(
				Views.zeroMin( Views.interval( img1, interval1 ) ),
				Views.zeroMin( Views.interval( img2, interval2 ) ),
				extension,
				new ArrayImgFactory<FloatType>(),
				new FloatType(), new ArrayImgFactory<ComplexFloatType>(),
				new ComplexFloatType(),
				service );
		
		final PhaseCorrelationPeak2 shiftPeak = PhaseCorrelation2.getShift(
				pcm,
				Views.zeroMin( Views.interval( img1, interval1 ) ),
				Views.zeroMin( Views.interval( img2, interval2 ) ),
				params.peaksToCheck,
				params.minOverlap,
				params.doSubpixel,
				service );

		// the best peak is horrible or no peaks were found at all, return null
		if (shiftPeak == null || Double.isInfinite( shiftPeak.getCrossCorr() ) )
			return null;

		final RealLocalizable shift;

		if ( shiftPeak.getSubpixelShift() == null )
			shift = shiftPeak.getShift();
		else
			shift = shiftPeak.getSubpixelShift();

		// adapt shift for the entire image, not only the overlapping parts
		final double[] entireIntervalShift = new double[ img1.numDimensions() ];

		for ( int d = 0; d < img1.numDimensions(); ++d )
		{
			// correct for the int/real coordinate mess
			final double intervalSubpixelOffset1 = interval1.realMin( d ) - localOverlap1.realMin( d ); // a_s
			final double intervalSubpixelOffset2 = interval2.realMin( d ) - localOverlap2.realMin( d ); // b_s

			final double localRasterShift = shift.getDoublePosition( d ); // d'
			System.out.println( intervalSubpixelOffset1 + "," + intervalSubpixelOffset2 + "," + localRasterShift);
			final double localRelativeShift = localRasterShift + ( intervalSubpixelOffset2 - intervalSubpixelOffset1 );

			// correct for the initial shift between the two inputs
			entireIntervalShift[ d ] = ( transformed2.realMin( d ) - transformed1.realMin( d ) ) + localRelativeShift;
		}
		
		return new ValuePair<>( entireIntervalShift, shiftPeak.getCrossCorr() );
	}
	
	public static <T extends RealType< T >, C extends Comparable< C >> List<PairwiseStitchingResult< C >> getPairwiseShifts(
			final Map<C, RandomAccessibleInterval<T>> rais,
			final Map<C, AbstractTranslation> translations,
			final PairwiseStitchingParameters params,
			final ExecutorService service)
	{
		List<C> indexes = new ArrayList<>(rais.keySet());
		Collections.sort( indexes );
		
		List<PairwiseStitchingResult< C >> result = new ArrayList<>();
		
		// got through all pairs with index1 < index2
		for(int i = 0; i < indexes.size(); i++)
		{
			for(int j = i+1; j < indexes.size(); j++)
			{
				Pair< double[], Double > resT = getShift( 	rais.get( indexes.get( i ) ), 
															rais.get( indexes.get( j ) ), 
															translations.get( indexes.get( i )),
															translations.get( indexes.get( j )), 
															params,
															service );
				if (resT != null)
				{
					Pair<C,C> key = new ValuePair< C, C >( indexes.get( i ), indexes.get( j ) );
					result.add( new PairwiseStitchingResult<>( key, resT.getA(), resT.getB() ) );
				}
			}
		}
		
		return result;
		
	}
	
	public static void main(String[] args)
	{
		final AffineTransform3D m = new AffineTransform3D();
		double scale = 200;
		m.set( scale, 0.0f, 0.0f, 0.0f, 
			   0.0f, scale, 0.0f, 0.0f,
			   0.0f, 0.0f, scale, 0.0f);
		
		final AffineTransform3D mShift = new AffineTransform3D();
		double shift = 100;
		mShift.set( 1.0f, 0.0f, 0.0f, shift, 
					0.0f, 1.0f, 0.0f, shift,
					0.0f, 0.0f, 1.0f, shift
					);
		final AffineTransform3D mShift2 = new AffineTransform3D();
		double shift2x = 1200;
		double shift2y = 300;
		mShift2.set( 1.0f, 0.0f, 0.0f, shift2x, 
					0.0f, 1.0f, 0.0f, shift2y,
					0.0f, 0.0f, 1.0f, 0.0f
					);
		
		final AffineTransform3D mShift3 = new AffineTransform3D();
		double shift3x = 500;
		double shift3y = 1300;
		mShift3.set( 1.0f, 0.0f, 0.0f, shift3x, 
					0.0f, 1.0f, 0.0f, shift3y,
					0.0f, 0.0f, 1.0f, 0.0f
					);
		
		
		AffineTransform3D m2 = m.copy();
		AffineTransform3D m3 = m.copy();
		m.preConcatenate( mShift );
		m2.preConcatenate( mShift2 );
		m3.preConcatenate( mShift3 );
		
		Interval start = new FinalInterval( new long[] {-399,-399,0},  new long[] {0, 0,1});
		List<Interval> intervals = FractalSpimDataGenerator.generateTileList( 
				start, 7, 6, 0.2f );
		
		List<Interval> falseStarts = FractalSpimDataGenerator.generateTileList( start, 7, 6, 0.30f );
		
		FractalSpimDataGenerator fsdg = new FractalSpimDataGenerator( 3 );
		fsdg.addFractal( m );
		fsdg.addFractal( m2 );
		fsdg.addFractal( m3 );
		
		
		Map<Integer, RandomAccessibleInterval<LongType >> rais = new HashMap<>();
		Map<Integer, AbstractTranslation> tr = new HashMap<>();
		
		List< AbstractTranslation > tileTranslations = FractalSpimDataGenerator.getTileTranslations( falseStarts);
		
		for (int i = 0; i < intervals.size(); i++){
			rais.put( i, fsdg.getImageAtInterval( intervals.get( i ) ) );
			tr.put( i, tileTranslations.get( i ) );
		}
		
		
		List<PairwiseStitchingResult< Integer >> pairwiseShifts = getPairwiseShifts( rais, tr, new PairwiseStitchingParameters(), Executors.newFixedThreadPool( Runtime.getRuntime().availableProcessors() ) );
	
		Map< Integer, double[] > globalOptimization = GlobalTileOptimization.twoRoundGlobalOptimization(3,
													new ArrayList<>(rais.keySet()), null, tr, pairwiseShifts, new GlobalOptimizationParameters() );
		
		System.out.println( globalOptimization );
	}
	
	
}