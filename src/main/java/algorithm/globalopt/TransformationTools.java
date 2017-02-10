package algorithm.globalopt;


import input.GenerateSpimData;

import spim.fiji.spimdata.stitchingresults.PairwiseStitchingResult;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import algorithm.DownsampleTools;
import algorithm.GroupedViewAggregator;
import algorithm.PairwiseStitching;
import algorithm.PairwiseStitchingParameters;
import algorithm.TransformTools;
import ij.IJ;
import input.GenerateSpimData;
import mpicbg.models.Tile;
import mpicbg.models.TranslationModel3D;
import mpicbg.spim.data.SpimData;
import mpicbg.spim.data.generic.sequence.AbstractSequenceDescription;
import mpicbg.spim.data.generic.sequence.BasicViewDescription;
import mpicbg.spim.data.registration.ViewRegistration;
import mpicbg.spim.data.registration.ViewRegistrations;
import mpicbg.spim.data.sequence.SequenceDescription;
import mpicbg.spim.data.sequence.ViewId;
import mpicbg.spim.io.IOFunctions;
import net.imglib2.Cursor;
import net.imglib2.Dimensions;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.realtransform.AbstractTranslation;
import net.imglib2.realtransform.AffineGet;
import net.imglib2.realtransform.AffineTransform3D;
import net.imglib2.realtransform.TranslationGet;
import net.imglib2.type.numeric.RealType;
import net.imglib2.util.Pair;
import net.imglib2.util.Util;
import net.imglib2.util.ValuePair;
import spim.Threads;
import spim.process.fusion.ImagePortion;

public class TransformationTools
{
	public static < T extends RealType< T > > Pair< double[], Double > computeStitching(
			final ViewId viewIdA,
			final ViewId viewIdB,
			final ViewRegistration vA,
			final ViewRegistration vB,
			final PairwiseStitchingParameters params,
			final AbstractSequenceDescription< ?,? extends BasicViewDescription<?>, ? > sd,
			final GroupedViewAggregator gva,
			final long[] downsampleFactors,
			final ExecutorService service )
	{
		// TODO: check if overlapping, else return immediately
		// TODO: can we ensure we have a ImgLoader here (BDV wraps ImgLoader into a BasicImgLaoder??)
		/*
		if (ImgLoader.class.isInstance( sd.getImgLoader() ))
		{
			Translation3D trA = new Translation3D( vA.getModel().getTranslation());
			Translation3D trB = new Translation3D( vB.getModel().getTranslation());
			Dimensions dimsA = ((ImgLoader)sd.getImgLoader()).getSetupImgLoader( viewIdA.getViewSetupId() ).getImageSize( viewIdA.getTimePointId() );
			Dimensions dimsB = ((ImgLoader)sd.getImgLoader()).getSetupImgLoader( viewIdB.getViewSetupId() ).getImageSize( viewIdB.getTimePointId() );
			
			if (!PairwiseStrategyTools.overlaps( dimsA, dimsB, trA, trB ))
				return null;
		}
		*/
		
			
		final RandomAccessibleInterval<T> img1;
		final RandomAccessibleInterval<T> img2;

		// the transformation that maps the downsampled image coordinates back to the original input(!) image space
		final AffineTransform3D dsCorrectionT = new AffineTransform3D();
		
		if (gva != null && GroupedViews.class.isInstance( viewIdA ))
		{
			img1 = gva.aggregate( (GroupedViews) viewIdA, sd, downsampleFactors, dsCorrectionT );	
			img2 = gva.aggregate( (GroupedViews) viewIdB, sd, downsampleFactors, dsCorrectionT );
		}
		else
		{
			img1 = DownsampleTools.openAndDownsample( sd.getImgLoader(), viewIdA, downsampleFactors, dsCorrectionT );
			img2 = DownsampleTools.openAndDownsample( sd.getImgLoader(), viewIdB, downsampleFactors, dsCorrectionT );
		}

		
		if (img1 == null || img2 == null)
		{
			IJ.log( "WARNING: Tried to open missing View when computing Stitching for " + viewIdA + " and " + 
						viewIdB + ". No link between those could be determined");
			return null;
		}
		
		
		boolean is2d = img1.numDimensions() == 2;
		
		
		// TODO: Test if 2d, and if then reduce dimensionality and ask for a 2d translation
		Pair< AffineGet, TranslationGet > t1 = TransformTools.getInitialTransforms( vA, is2d, dsCorrectionT );
		Pair< AffineGet, TranslationGet > t2 = TransformTools.getInitialTransforms( vB, is2d, dsCorrectionT );

		final Pair< double[], Double > result;
		
		if (params.doLucasKanade)
			result = PairwiseStitching.getShiftLucasKanade( img1, img2, t1.getB(), t2.getB(), params, service );
		else
			result = PairwiseStitching.getShift( img1, img2, t1.getB(), t2.getB(), params, service );

		if (result == null)
			return null;
		
		
		for (int i = 0; i< result.getA().length; ++i)			
			result.getA()[i] *= downsampleFactors[i];
		
		t1.getA().applyInverse( result.getA(), result.getA() );
		
		// shift
		//for (int i = 0; i< result.getA().length; ++i)
		//	result.getA()[i] = vB.getModel().get( i, 3 ) - result.getA()[i];
		
		System.out.println("integer shift: " + Util.printCoordinates(result.getA()));
		System.out.print("cross-corr: " + result.getB());

		
		service.shutdown();
		
		return result;
	}

	public static ArrayList< PairwiseStitchingResult<ViewId> > computePairs( 	final List< Pair< ViewId, ViewId > > pairs, 
																		final PairwiseStitchingParameters params, 
																		final ViewRegistrations vrs,
																		final AbstractSequenceDescription< ?, ? extends BasicViewDescription< ? >, ? > sd, 
																		final GroupedViewAggregator gva,
																		final long[] downsamplingFactors)
	{
		// set up executor service
		final ExecutorService serviceGlobal = Executors.newFixedThreadPool( Math.max( 2, Runtime.getRuntime().availableProcessors() / 2 ) );
		final ArrayList< Callable< Pair< Pair< ViewId, ViewId >, Pair< double[], Double > > > > tasks = new ArrayList<>();

		for ( final Pair< ViewId, ViewId > p : pairs )
		{
			tasks.add( new Callable< Pair< Pair< ViewId, ViewId >, Pair< double[], Double > > >()
			{
				@Override
				public Pair< Pair< ViewId, ViewId >, Pair< double[], Double > > call() throws Exception
				{
					IOFunctions.println( new Date( System.currentTimeMillis() ) + ": Compute pairwise: " + p.getA() + " <> " + p.getB() );

					final ExecutorService serviceLocal = Executors.newFixedThreadPool( Math.max( 2, Runtime.getRuntime().availableProcessors() / 4 ) );

					final Pair< double[], Double > result = computeStitching(
							p.getA(),
							p.getB(),
							vrs.getViewRegistration( p.getA() ),
							vrs.getViewRegistration( p.getB() ),
							params,
							sd,
							gva,
							downsamplingFactors,
							serviceLocal );

					IOFunctions.println( new Date( System.currentTimeMillis() ) + ": Compute pairwise: " + p.getA() + " <> " + p.getB() + ": r=" + result.getB() );

					return new ValuePair<>( p,  result );
				}
			});
		}

		final ArrayList< PairwiseStitchingResult<ViewId> > results = new ArrayList<>();

		try
		{
			// invokeAll() returns when all tasks are complete
			for ( final Future< Pair< Pair< ViewId, ViewId >, Pair< double[], Double > > > future : serviceGlobal.invokeAll( tasks ) )
			{
				final Pair< Pair< ViewId, ViewId >, Pair< double[], Double > > result = future.get();

				
				Pair< AffineGet, TranslationGet > initialTransformsA = TransformTools.getInitialTransforms( vrs.getViewRegistration( result.getA().getA() ), false, new AffineTransform3D() );
				Pair< AffineGet, TranslationGet > initialTransformsB = TransformTools.getInitialTransforms( vrs.getViewRegistration( result.getA().getB() ), false, new AffineTransform3D() );
				
				AffineGet mapBack = TransformTools.mapBackTransform( initialTransformsA.getA(), initialTransformsB.getA() );
				
				AffineTransform3D resT = new AffineTransform3D();
				resT.translate( result.getB().getA() );
				resT.preConcatenate( mapBack );
				
				
				Pair<Set<ViewId>, Set<ViewId>> setPair = new ValuePair< Set<ViewId>, Set<ViewId> >( new HashSet<>(), new HashSet<>() );
				if (result.getA().getA() instanceof GroupedViews)
				{ 
					setPair.getA().addAll( ((GroupedViews)result.getA().getA()).getViewIds() );
					setPair.getB().addAll( ((GroupedViews)result.getA().getB()).getViewIds() );
				}
				else
				{
					setPair.getA().add( result.getA().getA() );
					setPair.getA().add( result.getA().getB() );
				}
				
				
				// TODO: when does that really happen?
				if ( result.getB() != null)
					results.add( new PairwiseStitchingResult< ViewId >(setPair, resT, result.getB().getB() ) );
			}
		}
		catch ( final Exception e )
		{
			IOFunctions.println( "Failed to compute min/max: " + e );
			e.printStackTrace();
			return null;
		}

		/*
		for ( final Pair< ViewId, ViewId > p : pairs )
		{
			System.out.println( "Compute pairwise: " + p.getA() + " <> " + p.getB() );
			final Pair< double[], Double > result = computeStitching( p.getA(), p.getB(), vrs.getViewRegistration( p.getA() ), vrs.getViewRegistration( p.getB() ), params, sd , gva, downsamplingFactors, service );
			
			if (result != null)
				results.add( new PairwiseStitchingResult<ViewId>( p, result.getA(), result.getB() ) );
		}
		*/
		
		return results;
	}

	public static void main( String[] args )
	{
		final SpimData d = GenerateSpimData.grid3x2();
		final SequenceDescription sd = d.getSequenceDescription();
		final ViewRegistrations vr = d.getViewRegistrations();

		final boolean is2d = false;

		// select views to process
		final List< ViewId > rawViewIds = new ArrayList< ViewId >();
		rawViewIds.addAll( sd.getViewDescriptions().keySet() );
		Collections.sort( rawViewIds );

		// take together all views where the all attributes are the same except channel (i.e. group the channels)
		// they are now represented by the channel of the first ID (e.g. channelId=0)
		final List< GroupedViews > viewIds = GroupedViews.groupByChannel( rawViewIds, sd );

		// define fixed tiles
		final ArrayList< ViewId > fixedViews = new ArrayList< ViewId >();
		fixedViews.add( viewIds.get( 0 ) );

		// define groups (no checks in between Tiles of a group, they are transformed together)
		final ArrayList< ArrayList< ViewId > > groupedViews = new ArrayList< ArrayList< ViewId > >();

		// find all pairwise matchings that we need to compute
		final HashMap< ViewId, Dimensions > vd = new HashMap<>();
		final HashMap< ViewId, TranslationGet > vl = new HashMap<>();
		
		final long[] downsamplingFactors = new long[] {1,1,1};

		for ( final ViewId viewId : viewIds )
		{
			vd.put( viewId, sd.getViewDescription( viewId ).getViewSetup().getSize() );
			vl.put( viewId, TransformTools.getInitialTransforms( vr.getViewRegistration( viewId ), is2d, new AffineTransform3D()).getB() );
		}

		final List< Pair< ViewId, ViewId > > pairs = PairwiseStrategyTools.overlappingTiles(
				vd, vl, viewIds,
				fixedViews, groupedViews );
				
		// compute them
		final ArrayList< PairwiseStitchingResult <ViewId>> results = computePairs( pairs,
																new PairwiseStitchingParameters(), 
																d.getViewRegistrations(),
																d.getSequenceDescription() ,
																null,
																downsamplingFactors);

		// add correspondences
		
		if (true)
			return;
		
		for ( final ViewId v : fixedViews )
			System.out.println( "Fixed: " + v );

		
		GlobalOptimizationParameters params = new GlobalOptimizationParameters();
		// global opt
		final HashMap< ViewId, Tile< TranslationModel3D > > models =
				GlobalOpt.compute( new TranslationModel3D(), results, fixedViews, groupedViews , params);

		/*
		// save the corresponding detections and output result
		for ( final Pair< Pair< ViewId, ViewId >, PairwiseResult > p : result )
		{
			final InterestPointList listA = spimData.getViewInterestPoints().getViewInterestPointLists( p.getA().getA() ).getInterestPointList( "beads" );
			final InterestPointList listB = spimData.getViewInterestPoints().getViewInterestPointLists( p.getA().getB() ).getInterestPointList( "beads" );
			setCorrespondences( p.getB().getInliers(), p.getA().getA(), p.getA().getB(), "beads", "beads", listA, listB );

			System.out.println( p.getB().getFullDesc() );
		}
		

		// map-back model (useless as we fix the first one)
		final AffineTransform3D mapBack = computeMapBackModel(
				spimData.getSequenceDescription().getViewDescription( viewIds.get( 0 ) ).getViewSetup().getSize(),
				transformations.get( viewIds.get( 0 ) ).getModel(),
				models.get( viewIds.get( 0 ) ).getModel(),
				new RigidModel3D() );
		*/
	}
}
