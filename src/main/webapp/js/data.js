/**
 * TRAVELMATE — DATA LAYER & SCHEMA SPECIFICATIONS (PHASE 1)
 * 
 * Cleanly partitioned sample datasets for Indian travel destinations.
 * In Phase 2, these structures map 1:1 to Java Model beans:
 *  - com.travelmate.model.Trip
 *  - com.travelmate.model.ItineraryDay
 *  - com.travelmate.model.ItineraryStop
 *  - com.travelmate.model.TransportOption
 *  - com.travelmate.model.Hotel
 *  - com.travelmate.model.Restaurant
 *  - com.travelmate.model.BudgetItem
 * 
 * Endpoints targeted for Phase 2:
 *  GET/POST   /api/trips
 *  GET/PUT    /api/trips/{id}
 *  GET/POST   /api/trips/{id}/stops
 *  GET        /api/destinations
 *  GET        /api/transport/search
 *  GET        /api/hotels/search
 *  GET        /api/restaurants/search
 *  GET/POST   /api/budget/{tripId}
 *  GET        /api/weather/{destinationId}
 */

const TRAVELMATE_DATA = {
  // Destination Catalogs
  destinations: [
    {
      id: "mysuru",
      name: "Mysuru",
      state: "Karnataka",
      tagline: "City of Palaces, Sandalwood & Royal Heritage",
      image: "https://images.unsplash.com/photo-1590050752117-238cb0fb12b1?auto=format&fit=crop&w=800&q=80",
      description: "Steeped in royal grandeur, Mysuru captivates travelers with its illuminated palace, silk bazaars, Chamundi hills, and ancient stone temples.",
      suggestedDays: 3,
      bestSeason: "October to March (Pleasant winter & Dasara celebrations)",
      budgetRange: "₹4,500 – ₹12,000 / person",
      coordinates: [12.3051, 76.6551],
      category: "Heritage",
      region: "South",
      highlights: ["Mysore Palace", "Chamundi Hill", "Brindavan Gardens", "Devaraja Market", "St. Philomena's Cathedral"],
      cuisine: ["Mysore Pak", "Mylari Masala Dosa", "Filter Coffee", "Bisi Bele Bath"]
    },
    {
      id: "ooty",
      name: "Ooty",
      state: "Tamil Nadu",
      tagline: "Queen of Hill Stations & Nilgiri Mountain Railway",
      image: "https://images.unsplash.com/photo-1589182373726-e4f658ab50f0?auto=format&fit=crop&w=800&q=80",
      description: "Nestled in the Nilgiri Hills, Ooty offers mist-covered tea plantations, historic toy trains, serene lakes, and crisp mountain breeze.",
      suggestedDays: 3,
      bestSeason: "October to June",
      budgetRange: "₹6,000 – ₹15,000 / person",
      coordinates: [11.4102, 76.6950],
      category: "Hill Station",
      region: "South",
      highlights: ["Botanical Gardens", "Ooty Lake", "Doddabetta Peak", "Nilgiri Mountain Railway", "Tea Factory"],
      cuisine: ["Homemade Chocolates", "Nilgiri Tea", "Steaming Vada & Sambar"]
    },
    {
      id: "jaipur",
      name: "Jaipur",
      state: "Rajasthan",
      tagline: "The Pink City of Forts, Palaces & Vibrant Bazars",
      image: "https://images.unsplash.com/photo-1599661046289-e31897846e41?auto=format&fit=crop&w=800&q=80",
      description: "The capital of Rajasthan, famed for honeycomb Hawa Mahal, imposing Amber Fort, royal courtyards, and handcrafted jewelry.",
      suggestedDays: 3,
      bestSeason: "November to February",
      budgetRange: "₹7,000 – ₹18,000 / person",
      coordinates: [26.9124, 75.7873],
      category: "Heritage",
      region: "North",
      highlights: ["Amber Fort", "Hawa Mahal", "City Palace", "Jantar Mantar", "Nahargarh Fort"],
      cuisine: ["Dal Baati Churma", "Pyaaz Kachori", "Ghevar", "Lal Maas"]
    },
    {
      id: "goa",
      name: "Goa",
      state: "Goa",
      tagline: "Sun-Kissed Beaches, Portuguese Heritage & Coastal Cuisine",
      image: "https://images.unsplash.com/photo-1512343879784-a960bf40e7f2?auto=format&fit=crop&w=800&q=80",
      description: "India's premier coastal haven, combining palm-fringed sands, whitewashed Baroque churches, water sports, and laid-back cafes.",
      suggestedDays: 4,
      bestSeason: "November to March",
      budgetRange: "₹8,000 – ₹22,000 / person",
      coordinates: [15.2993, 74.1240],
      category: "Beach",
      region: "West",
      highlights: ["Palolem Beach", "Basilica of Bom Jesus", "Fort Aguada", "Dudhsagar Falls", "Anjuna Flea Market"],
      cuisine: ["Goan Fish Curry", "Prawn Balchão", "Bebinca", "Poee with Chorizo"]
    },
    {
      id: "munnar",
      name: "Munnar",
      state: "Kerala",
      tagline: "Rolling Emerald Tea Hills & Misty Waterfalls",
      image: "https://images.unsplash.com/photo-1506461883276-594a12b11cf3?auto=format&fit=crop&w=800&q=80",
      description: "An idyllic high-altitude getaway with carpeted green tea estates, endangered Nilgiri Tahr at Eravikulam, and cool mountain clouds.",
      suggestedDays: 3,
      bestSeason: "September to April",
      budgetRange: "₹6,500 – ₹16,000 / person",
      coordinates: [10.0889, 77.0595],
      category: "Nature",
      region: "South",
      highlights: ["Eravikulam National Park", "Mattupetty Dam", "Tea Museum", "Top Station", "Attukad Waterfalls"],
      cuisine: ["Kerala Sadya", "Appam with Stew", "Cardamom Tea", "Karimeen Pollichathu"]
    },
    {
      id: "hyderabad",
      name: "Hyderabad",
      state: "Telangana",
      tagline: "City of Pearls, Qutb Shahi Forts & World-Famous Biryani",
      image: "https://images.unsplash.com/photo-1605649487212-47bdab064df7?auto=format&fit=crop&w=800&q=80",
      description: "Where 400-year-old Nizami heritage blends seamlessly with cutting-edge tech parks, aromatic dum biryani, and monolithic Charminar.",
      suggestedDays: 3,
      bestSeason: "October to March",
      budgetRange: "₹5,000 – ₹14,000 / person",
      coordinates: [17.3850, 78.4867],
      category: "Heritage",
      region: "South",
      highlights: ["Charminar", "Golconda Fort", "Chowmahalla Palace", "Ramoji Film City", "Hussain Sagar Lake"],
      cuisine: ["Hyderabadi Dum Biryani", "Mirchi Ka Salan", "Double Ka Meetha", "Irani Chai & Osmania Biscuits"]
    }
  ],

  // Active / Sample Trip: Mysuru 3-Day Journey
  activeTrip: {
    id: "trip-mysuru-2026",
    title: "Mysuru Royal Heritage & Nature Trails",
    destination: "Mysuru",
    origin: "Bengaluru",
    startDate: "2026-11-14",
    endDate: "2026-11-16",
    daysCount: 3,
    travelers: 2,
    budgetTotal: 18000,
    budgetCategory: "Moderate",
    interests: ["Heritage", "Food", "Nature", "Photography"],
    coverImage: "https://images.unsplash.com/photo-1590050752117-238cb0fb12b1?auto=format&fit=crop&w=1200&q=80",
    accommodation: {
      name: "Grand Mercure Mysuru",
      type: "Premium Hotel",
      address: "New Sayyaji Rao Road, Bamboo Bazar, Mysuru",
      pricePerNight: 4200,
      nights: 2
    },
    days: [
      {
        dayNumber: 1,
        title: "Royal Palaces, Grand Bazaars & Evening Illumination",
        date: "2026-11-14",
        summary: "3 stops • 4.5 hrs planned • ~14 km local transit • ₹1,250 est. spend",
        stops: [
          {
            id: "stop-101",
            name: "Mysore Palace (Amba Vilas)",
            category: "Heritage",
            categoryIcon: "bi-bank",
            time: "09:30 AM",
            duration: "2.5 hrs",
            lat: 12.3051,
            lng: 76.6551,
            cost: 100,
            image: "https://images.unsplash.com/photo-1590050752117-238cb0fb12b1?auto=format&fit=crop&w=600&q=80",
            notes: "Must see the Durbar Hall and golden royal throne. Photography permitted in outer corridors. Audio guide recommended.",
            transitToNext: {
              mode: "Auto Rickshaw",
              icon: "bi-taxi-front",
              duration: "10 mins",
              distance: "2.3 km",
              cost: 50
            }
          },
          {
            id: "stop-102",
            name: "Lunch at Mylari Hotel (Old Heritage Branch)",
            category: "Food",
            categoryIcon: "bi-cup-hot",
            time: "12:45 PM",
            duration: "1.0 hr",
            lat: 12.3115,
            lng: 76.6620,
            cost: 250,
            image: "https://images.unsplash.com/photo-1630383249896-424e482df921?auto=format&fit=crop&w=600&q=80",
            notes: "World famous soft Mylari butter dosas served on plantain leaves with freshly brewed South Indian filter coffee.",
            transitToNext: {
              mode: "Walk",
              icon: "bi-person-walking",
              duration: "8 mins",
              distance: "600 m",
              cost: 0
            }
          },
          {
            id: "stop-103",
            name: "Devaraja Market Heritage Walk",
            category: "Culture",
            categoryIcon: "bi-basket",
            time: "02:15 PM",
            duration: "1.5 hrs",
            lat: 12.3102,
            lng: 76.6508,
            cost: 0,
            image: "https://images.unsplash.com/photo-1534447677768-be436bb09401?auto=format&fit=crop&w=600&q=80",
            notes: "130-year-old vibrant market full of fragrant jasmine garlands, sandalwood oils, and colorful heaps of kumkum powder.",
            transitToNext: {
              mode: "Cab",
              icon: "bi-car-front",
              duration: "15 mins",
              distance: "3.8 km",
              cost: 120
            }
          },
          {
            id: "stop-104",
            name: "St. Philomena's Cathedral & Evening Palace Lights",
            category: "Architecture",
            categoryIcon: "bi-building",
            time: "04:30 PM",
            duration: "2.0 hrs",
            lat: 12.3210,
            lng: 76.6582,
            cost: 0,
            image: "https://images.unsplash.com/photo-1548013146-72479768bada?auto=format&fit=crop&w=600&q=80",
            notes: "Neo-Gothic cathedral with twin 175-foot spires. Then return to Palace grounds for the Sunday 7 PM grand lighting.",
            transitToNext: null
          }
        ]
      },
      {
        dayNumber: 2,
        title: "Sacred Chamundi Hills & Zoo Sanctuary",
        date: "2026-11-15",
        summary: "3 stops • 5.0 hrs planned • ~22 km local transit • ₹1,600 est. spend",
        stops: [
          {
            id: "stop-201",
            name: "Sri Chamundeshwari Temple & Nandi Monolith",
            category: "Temples",
            categoryIcon: "bi-flower1",
            time: "08:30 AM",
            duration: "2.5 hrs",
            lat: 12.2743,
            lng: 76.6710,
            cost: 100,
            image: "https://images.unsplash.com/photo-1609766857041-ed402ea8069a?auto=format&fit=crop&w=600&q=80",
            notes: "Perched 3,489 feet high. Catch panoramic views of the city. Stop at the massive 16-foot carved monolithic Nandi statue.",
            transitToNext: {
              mode: "Cab",
              icon: "bi-car-front",
              duration: "25 mins",
              distance: "12 km",
              cost: 240
            }
          },
          {
            id: "stop-202",
            name: "Mysuru Zoo (Sri Chamarajendra Zoological Gardens)",
            category: "Nature",
            categoryIcon: "bi-tree",
            time: "11:45 AM",
            duration: "2.5 hrs",
            lat: 12.3023,
            lng: 76.6668,
            cost: 200,
            image: "https://images.unsplash.com/photo-1534188753412-3e26d0d618d6?auto=format&fit=crop&w=600&q=80",
            notes: "One of India's oldest and best-kept zoos, spanning 157 acres with rare aviaries and shaded walking loops.",
            transitToNext: {
              mode: "Cab",
              icon: "bi-car-front",
              duration: "35 mins",
              distance: "18 km",
              cost: 350
            }
          },
          {
            id: "stop-203",
            name: "Brindavan Gardens (Outdoor Musical Fountains)",
            category: "Gardens",
            categoryIcon: "bi-water",
            time: "03:30 PM",
            duration: "2.5 hrs",
            lat: 12.4243,
            lng: 76.5732,
            cost: 100,
            image: "https://images.unsplash.com/photo-1585320806297-9794b3e4eeae?auto=format&fit=crop&w=600&q=80",
            notes: "Terraced botanical gardens beside KRS Dam. [WEATHER ALERT: Outdoor rain forecast simulated for this time!]",
            isWeatherImpacted: true,
            transitToNext: null
          }
        ]
      },
      {
        dayNumber: 3,
        title: "Artisanal Silk, Rail Heritage & Return",
        date: "2026-11-16",
        summary: "3 stops • 4.0 hrs planned • ~10 km local transit • ₹1,100 est. spend",
        stops: [
          {
            id: "stop-301",
            name: "KSIC Mysore Silk Weaving Factory",
            category: "Shopping",
            categoryIcon: "bi-bag-heart",
            time: "10:00 AM",
            duration: "1.5 hrs",
            lat: 12.2985,
            lng: 76.6432,
            cost: 50,
            image: "https://images.unsplash.com/photo-1610030469983-98e550d6193c?auto=format&fit=crop&w=600&q=80",
            notes: "Watch pure mulberry silk sarees being woven with certified gold zari threads on heritage looms.",
            transitToNext: {
              mode: "Auto Rickshaw",
              icon: "bi-taxi-front",
              duration: "12 mins",
              distance: "3.5 km",
              cost: 60
            }
          },
          {
            id: "stop-302",
            name: "Mysuru Rail Museum & Vintage Saloons",
            category: "Heritage",
            categoryIcon: "bi-train-front",
            time: "12:00 PM",
            duration: "1.5 hrs",
            lat: 12.3168,
            lng: 76.6445,
            cost: 50,
            image: "https://images.unsplash.com/photo-1515165562839-978bbcf18277?auto=format&fit=crop&w=600&q=80",
            notes: "Second museum of its kind in India after New Delhi. Houses the Maharani's personal royal dining carriage.",
            transitToNext: {
              mode: "Auto Rickshaw",
              icon: "bi-taxi-front",
              duration: "10 mins",
              distance: "2.1 km",
              cost: 50
            }
          },
          {
            id: "stop-303",
            name: "Guru Sweets — Authentic Mysore Pak Tasting",
            category: "Food",
            categoryIcon: "bi-cup-hot",
            time: "02:00 PM",
            duration: "45 mins",
            lat: 12.3108,
            lng: 76.6521,
            cost: 200,
            image: "https://images.unsplash.com/photo-1601050690597-df0568f70950?auto=format&fit=crop&w=600&q=80",
            notes: "Owned by descendants of Kakasura Madappa, the royal chef who invented Mysore Pak for the Maharaja in 1935.",
            transitToNext: null
          }
        ]
      }
    ]
  },

  // Transport Options Catalog
  transports: [
    {
      id: "tr-001",
      mode: "train",
      modeName: "Train",
      icon: "bi-train-front",
      provider: "Indian Railways",
      number: "Provider listing",
      depTime: "Varies by service",
      arrTime: "Varies by service",
      duration: "1h 45m",
      price: 540,
      priceCategory: "Indicative estimate",
      availability: "Check provider for live availability",
      features: ["AC Chair Car", "Breakfast Included", "High-Speed 130 km/h", "Clean Restrooms"]
    },
    {
      id: "tr-002",
      mode: "train",
      modeName: "Train",
      icon: "bi-train-front",
      provider: "Indian Railways",
      number: "Provider listing",
      depTime: "Varies by service",
      arrTime: "Varies by service",
      duration: "Check route",
      price: 135,
      priceCategory: "Indicative estimate",
      availability: "Check provider for live availability",
      features: ["Second Seating (2S)", "Scenic Expressway Views", "Onboard Snacks"]
    },
    {
      id: "tr-003",
      mode: "bus",
      modeName: "Bus",
      icon: "bi-bus-front",
      provider: "KSRTC Bus Services",
      number: "Provider listing",
      depTime: "Varies by service",
      arrTime: "Varies by service",
      duration: "Check route",
      price: 380,
      priceCategory: "Indicative estimate",
      availability: "Check provider for live availability",
      features: ["Volvo Multi-Axle AC", "Water Bottle Provided", "Reclining Seats"]
    },
    {
      id: "tr-004",
      mode: "bus",
      modeName: "Bus",
      icon: "bi-bus-front",
      provider: "KSRTC Bus Services",
      number: "Provider listing",
      depTime: "Varies by service",
      arrTime: "Varies by service",
      duration: "Check route",
      price: 800,
      priceCategory: "Indicative estimate",
      availability: "Check provider for live availability",
      features: ["Luggage Storage", "Live Flight Tracking"]
    },
    {
      id: "tr-005",
      mode: "flight",
      modeName: "Flight",
      icon: "bi-airplane",
      provider: "Domestic Airline Providers",
      number: "Provider listing",
      depTime: "Varies by service",
      arrTime: "Varies by service",
      duration: "50m",
      price: 1850,
      priceCategory: "Indicative estimate",
      availability: "Check provider for live availability",
      features: ["ATR-72 Turboprop", "15kg Check-in Baggage", "Quick Boarding"]
    },
    {
      id: "tr-006",
      mode: "car",
      modeName: "Cab / Self-Drive",
      icon: "bi-car-front",
      provider: "Cab and Self-Drive Providers",
      number: "Provider listing",
      depTime: "Varies by service",
      arrTime: "Varies by service",
      duration: "Check route",
      price: 2450,
      priceCategory: "Indicative estimate",
      availability: "Check provider for live availability",
      features: ["Door-to-door Convenience", "Expressway Tolls Included", "Photogenic Pit-Stops", "AC Vehicle"]
    }
  ],

  // Hotels Catalog
  hotels: [
    {
      id: "ht-001",
      name: "Grand Mercure Mysuru — Accor",
      destination: "Mysuru",
      type: "Luxury / Heritage",
      rating: 4.6,
      reviewsCount: 1420,
      image: "https://images.unsplash.com/photo-1566073771259-6a8506099945?auto=format&fit=crop&w=700&q=80",
      pricePerNight: 4200,
      distanceToCenter: "3.2 km from Mysore Palace",
      amenities: ["Free High-Speed Wi-Fi", "Swimming Pool", "Complimentary Breakfast", "Free Parking", "Spa & Wellness"],
      description: "Set in the historic city, this premium hotel blends local Hoysala architecture with international Accor hospitality.",
      badge: "TravelMate Top Pick"
    },
    {
      id: "ht-002",
      name: "Radisson Blu Plaza Hotel Mysore",
      destination: "Mysuru",
      type: "5-Star Luxury",
      rating: 4.7,
      reviewsCount: 2310,
      image: "https://images.unsplash.com/photo-1582719508461-905c673771fd?auto=format&fit=crop&w=700&q=80",
      pricePerNight: 5800,
      distanceToCenter: "1.5 km from Mysore Zoo & Palace",
      amenities: ["Panoramic Mountain View", "Rooftop Pool", "Fine Dining", "Fitness Center", "Airport Shuttle"],
      description: "Overlooking the Chamundi Hills, with immediate proximity to the Golf Club and Mysore Palace.",
      badge: "Luxury Favorite"
    },
    {
      id: "ht-003",
      name: "Roamer Heritage Haveli & Homestay",
      destination: "Mysuru",
      type: "Heritage Homestay",
      rating: 4.5,
      reviewsCount: 680,
      image: "https://images.unsplash.com/photo-1520250497591-112f2f40a3f4?auto=format&fit=crop&w=700&q=80",
      pricePerNight: 1950,
      distanceToCenter: "1.1 km from Devaraja Market",
      amenities: ["Traditional Courtyard", "Home-Cooked Breakfast", "Free Wi-Fi", "Bicycle Rental", "Library"],
      description: "A meticulously restored 1920s courtyard home offering tranquil heritage vibes and personal host attention.",
      badge: "Best Value"
    },
    {
      id: "ht-004",
      name: "The Viceroy Comforts",
      destination: "Mysuru",
      type: "Budget Boutique",
      rating: 4.2,
      reviewsCount: 890,
      image: "https://images.unsplash.com/photo-1590490360182-c33d57733427?auto=format&fit=crop&w=700&q=80",
      pricePerNight: 1450,
      distanceToCenter: "500 m walking to Palace North Gate",
      amenities: ["AC Rooms", "24/7 Front Desk", "Pure Veg Restaurant", "Free Wi-Fi"],
      description: "Unbeatable central location right across the street from the Maharaja Palace gates.",
      badge: "Prime Location"
    }
  ],

  // Restaurants Catalog
  restaurants: [
    {
      id: "rest-001",
      name: "Hotel Original Vinayaka Mylari",
      destination: "Mysuru",
      cuisine: "Authentic Karnataka Dosa & Filter Coffee",
      mealType: "Breakfast / Snack",
      pricePerPerson: 120,
      rating: 4.8,
      reviewsCount: 3800,
      image: "https://images.unsplash.com/photo-1630383249896-424e482df921?auto=format&fit=crop&w=600&q=80",
      distanceToStop: "Near Nazarbad & Mysore Palace",
      dietary: "Vegetarian Friendly",
      specialty: "Mylari Special Butter Dosa with Sagoo"
    },
    {
      id: "rest-002",
      name: "RRR Biryani & Andhra Restaurant",
      destination: "Mysuru",
      cuisine: "Spicy Andhra Biryani & Coastal Non-Veg",
      mealType: "Lunch / Dinner",
      pricePerPerson: 350,
      rating: 4.5,
      reviewsCount: 2900,
      image: "https://images.unsplash.com/photo-1563379091339-03b21ab4a4f8?auto=format&fit=crop&w=600&q=80",
      distanceToStop: "Gandhi Square, 800m from Palace",
      dietary: "Non-Veg Specialty",
      specialty: "Mutton Biryani served on Banana Leaf"
    },
    {
      id: "rest-003",
      name: "Gufha Restaurant — The President Hotel",
      destination: "Mysuru",
      cuisine: "Indian, Mughlai & Frontier Kebabs",
      mealType: "Dinner",
      pricePerPerson: 550,
      rating: 4.4,
      reviewsCount: 1650,
      image: "https://images.unsplash.com/photo-1517248135467-4c7edcad34c4?auto=format&fit=crop&w=600&q=80",
      distanceToStop: "Irwin Road, Mysore Center",
      dietary: "Multi-Cuisine",
      specialty: "Cave-themed ambient dining with Kakori Kebab"
    },
    {
      id: "rest-004",
      name: "Depth N Green Cafe",
      destination: "Mysuru",
      cuisine: "Organic, Vegan & Artisanal Bakery",
      mealType: "Breakfast / Cafe",
      pricePerPerson: 280,
      rating: 4.6,
      reviewsCount: 940,
      image: "https://images.unsplash.com/photo-1554118811-1e0d58224f24?auto=format&fit=crop&w=600&q=80",
      distanceToStop: "Gokulam (Yoga District)",
      dietary: "Vegan & Health-Conscious",
      specialty: "Avocado Sourdough Toast & Cold Brew"
    }
  ],

  // Weather Forecast & Smart Alternative Case
  weather: {
    destination: "Mysuru",
    currentCondition: "Partly Cloudy",
    currentTemp: "27°C",
    humidity: "62%",
    wind: "14 km/h",
    fiveDayForecast: [
      { date: "Nov 14", day: "Fri", condition: "Sunny", icon: "bi-sun", high: 29, low: 18, rainProb: "10%" },
      { date: "Nov 15", day: "Sat", condition: "Thunderstorms / Rain", icon: "bi-cloud-lightning-rain", high: 26, low: 19, rainProb: "75%", alert: true },
      { date: "Nov 16", day: "Sun", condition: "Scattered Clouds", icon: "bi-cloud-sun", high: 28, low: 17, rainProb: "20%" },
      { date: "Nov 17", day: "Mon", condition: "Pleasant & Clear", icon: "bi-sun", high: 30, low: 18, rainProb: "5%" },
      { date: "Nov 18", day: "Tue", condition: "Clear", icon: "bi-brightness-high", high: 29, low: 17, rainProb: "5%" }
    ],
    // Smart Alternative Demonstration Scenario
    alternativeProposal: {
      affectedDayNumber: 2,
      affectedStopId: "stop-203",
      originalStop: {
        name: "Brindavan Gardens (Outdoor Fountain Promenade)",
        category: "Outdoor Gardens",
        timing: "03:30 PM – 06:00 PM",
        cost: 100,
        type: "Outdoor"
      },
      recommendedStop: {
        name: "Mysuru Rail Museum & Sand Sculpture Art Gallery",
        category: "Indoor Heritage & Covered Pavilions",
        timing: "03:30 PM – 05:45 PM",
        cost: 60,
        type: "Indoor / Covered",
        image: "https://images.unsplash.com/photo-1515165562839-978bbcf18277?auto=format&fit=crop&w=600&q=80",
        notes: "Completely sheltered royal carriages and indoor sand art exhibition. Unaffected by rainfall."
      },
      reason: "Heavy afternoon shower & thunderstorm forecast (75% probability) between 3:00 PM and 6:00 PM.",
      scheduleImpact: "Same duration (2h 15m), zero schedule delay.",
      costImpact: "Saves ₹40 per traveler on entry fees."
    }
  },

  // Budget Breakdown Data
  budget: {
    totalLimit: 20000,
    estimatedTotal: 15300,
    actualSpend: 1850,
    currency: "INR (₹)",
    categories: [
      { id: "cat-transport", name: "Outbound & Return Transit", estimated: 3800, actual: 1080, icon: "bi-train-front", color: "#0D3B4C" },
      { id: "cat-hotel", name: "Accommodation (2 Nights)", estimated: 8400, actual: 0, icon: "bi-building", color: "#2A9D8F" },
      { id: "cat-local", name: "Local Cab & Auto Transit", estimated: 1200, actual: 320, icon: "bi-taxi-front", color: "#F4A261" },
      { id: "cat-food", name: "Food & Dining", estimated: 2400, actual: 450, icon: "bi-cup-hot", color: "#E76F51" },
      { id: "cat-attractions", name: "Attraction Tickets", estimated: 900, actual: 0, icon: "bi-ticket-perforated", color: "#3B82F6" },
      { id: "cat-misc", name: "Miscellaneous & Tips", estimated: 600, actual: 0, icon: "bi-gift", color: "#94A3B8" }
    ],
    items: [
      { id: "bgt-1", name: "Bengaluru to Mysuru Vande Bharat Tickets (x2)", category: "Outbound & Return Transit", amount: 1080, status: "Actual", day: "Day 1" },
      { id: "bgt-2", name: "Grand Mercure Mysuru Stay (2 nights)", category: "Accommodation (2 Nights)", amount: 8400, status: "Estimated", day: "Days 1–3" },
      { id: "bgt-3", name: "Mysore Palace Entry & Audio Guides (x2)", category: "Attraction Tickets", amount: 200, status: "Estimated", day: "Day 1" },
      { id: "bgt-4", name: "Mylari Dosa & Lunch", category: "Food & Dining", amount: 450, status: "Actual", day: "Day 1" },
      { id: "bgt-5", name: "Local Auto Rickshaw Transfers", category: "Local Cab & Auto Transit", amount: 320, status: "Actual", day: "Day 1" },
      { id: "bgt-6", name: "Chamundi Hill Cab roundtrip", category: "Local Cab & Auto Transit", amount: 550, status: "Estimated", day: "Day 2" },
      { id: "bgt-7", name: "Zoo Tickets & Battery Cart (x2)", category: "Attraction Tickets", amount: 400, status: "Estimated", day: "Day 2" },
      { id: "bgt-8", name: "Brindavan / Rail Museum Entry", category: "Attraction Tickets", amount: 200, status: "Estimated", day: "Day 2" },
      { id: "bgt-9", name: "Dinner at Gufha Cave Restaurant", category: "Food & Dining", amount: 1200, status: "Estimated", day: "Day 2" },
      { id: "bgt-10", name: "Return KSRTC Airavat / Train Tickets (x2)", category: "Outbound & Return Transit", amount: 760, status: "Estimated", day: "Day 3" },
      { id: "bgt-11", name: "Mysore Pak Boxes & Silk Souvenirs", category: "Miscellaneous & Tips", amount: 600, status: "Estimated", day: "Day 3" }
    ]
  },

  // My Trips List
  tripsList: [
    {
      id: "trip-mysuru-2026",
      title: "Mysuru Royal Heritage & Nature Trails",
      destination: "Mysuru, Karnataka",
      dates: "Nov 14 – Nov 16, 2026",
      duration: "3 Days, 2 Nights",
      travelers: 2,
      budget: 18000,
      image: "https://images.unsplash.com/photo-1590050752117-238cb0fb12b1?auto=format&fit=crop&w=700&q=80",
      status: "Upcoming",
      badgeClass: "tm-badge-primary",
      stopsCount: 10,
      lastUpdated: "Today at 10:24 AM"
    },
    {
      id: "trip-ooty-draft",
      title: "Nilgiri Mountain Tea Escape",
      destination: "Ooty, Tamil Nadu",
      dates: "Dec 20 – Dec 23, 2026",
      duration: "4 Days, 3 Nights",
      travelers: 4,
      budget: 28000,
      image: "https://images.unsplash.com/photo-1589182373726-e4f658ab50f0?auto=format&fit=crop&w=700&q=80",
      status: "Draft",
      badgeClass: "tm-badge-warning",
      stopsCount: 7,
      lastUpdated: "3 days ago"
    },
    {
      id: "trip-jaipur-completed",
      title: "Royal Rajputana Palaces & Forts",
      destination: "Jaipur, Rajasthan",
      dates: "Jan 10 – Jan 13, 2026",
      duration: "4 Days, 3 Nights",
      travelers: 2,
      budget: 22000,
      image: "https://images.unsplash.com/photo-1599661046289-e31897846e41?auto=format&fit=crop&w=700&q=80",
      status: "Completed",
      badgeClass: "tm-badge-success",
      stopsCount: 12,
      lastUpdated: "2 months ago"
    }
  ]
};

// Export to window for global access across vanilla JS pages
window.TRAVELMATE_DATA = TRAVELMATE_DATA;
