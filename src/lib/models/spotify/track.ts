import {deserialize} from "cerialize";

/**
 * Note that I generated these class files by using https://app.quicktype.io/#l=ts to define interfaces,
 * then converted them to classes and added the deserialize annotation to use Cerialize with them.
 *
 * Out of the box, the converters generated by https://app.quicktype.io/#l=ts didn't work since some of these are
 * optional types and I didn't see a way to support that since it type checked against "example instances" e.g.
 * '""' for string, and there isn't an "example instance" that "typeof" would work for null OR string...
 */

export class Track {
    @deserialize album: Album;
    @deserialize artists: ArtistForTrack[];
    @deserialize available_markets: string[];
    @deserialize disc_number: number;
    @deserialize duration_ms: number;
    @deserialize explicit: boolean;
    @deserialize external_ids: ExternalIDS;
    @deserialize external_urls: ExternalUrls;
    @deserialize href: string;
    @deserialize id: string;
    @deserialize is_local: boolean;
    @deserialize name: string;
    @deserialize popularity: number;
    @deserialize preview_url: string | null;
    @deserialize track_number: number;
    @deserialize type: string;
    @deserialize uri: string;
}

export class Album {
    @deserialize album_type: string;
    @deserialize artists: ArtistForTrack[];
    @deserialize available_markets: string[];
    @deserialize external_urls: ExternalUrls;
    @deserialize href: string;
    @deserialize id: string;
    @deserialize images: Image[];
    @deserialize name: string;
    // TODO: this is not being parsed as a Date even though that's what the first api thing wanted it to be!
    @deserialize release_date: string;
    @deserialize release_date_precision: string;
    @deserialize total_tracks: number;
    @deserialize type: string;
    @deserialize uri: string;
}

export class ArtistForTrack {
    @deserialize external_urls: ExternalUrls;
    @deserialize href: string;
    @deserialize id: string;
    @deserialize name: string;
    @deserialize type: string;
    @deserialize uri: string;
}

export class ExternalUrls {
    @deserialize spotify: string;
}

export class Image {
    @deserialize height: number;
    @deserialize url: string;
    @deserialize width: number;
}

export class ExternalIDS {
    @deserialize isrc: string;
}